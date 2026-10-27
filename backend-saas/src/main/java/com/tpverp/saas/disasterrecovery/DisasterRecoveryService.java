package com.tpverp.saas.disasterrecovery;

import static com.tpverp.saas.disasterrecovery.DisasterRecoveryModels.*;
import com.fasterxml.jackson.databind.*;
import com.tpverp.saas.admin.AdminAuditService;
import com.tpverp.saas.backup.*;
import com.tpverp.saas.license.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Support permission is enforced on every admin route. Destination authority is bound to one key and artifact. */
@Service
@Transactional
public class DisasterRecoveryService {
    private final JdbcTemplate jdbc;
    private final SaasInstallationRepository installations;
    private final CloudBackupVaultService vault;
    private final TokenHasher tokens;
    private final AdminAuditService audit;
    private final ObjectMapper mapper;
    private final Clock clock;
    public DisasterRecoveryService(JdbcTemplate jdbc,SaasInstallationRepository installations,CloudBackupVaultService vault,
            TokenHasher tokens,AdminAuditService audit,ObjectMapper mapper,Clock clock) {
        this.jdbc=jdbc;this.installations=installations;this.vault=vault;this.tokens=tokens;this.audit=audit;this.mapper=mapper;this.clock=clock;
    }
    public State create(UUID ticket,Create request) {
        var installation=lock(request.installationId()); ticket(ticket,installation);
        if(!installation.isActive()) throw conflict("RECOVERY_INSTALLATION_REVOKED");
        String fingerprint=RecoveryCryptography.fingerprint(request.destinationPublicKey());
        var existing=find(request.requestId());
        if(existing!=null) {
            if(!ticket.equals(existing.ticket()) || !request.installationId().equals(existing.installation())
                    || !request.backupId().equals(existing.backup()) || !fingerprint.equals(existing.fingerprint())) throw conflict("RECOVERY_REQUEST_CONFLICT");
            return state(existing);
        }
        if(jdbc.queryForObject("select count(*) from saas_disaster_recovery where installation_id=? and state not in ('ACTIVATED','CANCELLED')",Long.class,request.installationId())>0)
            throw conflict("RECOVERY_ALREADY_PENDING");
        var descriptor=vault.descriptor(request.installationId(),request.backupId());
        if(descriptor.portableSecretsVersion()!=1 || descriptor.recoveryKeyVersion()!=2) throw conflict("RECOVERY_PORTABLE_BACKUP_REQUIRED");
        String sourceHash=jdbc.queryForObject("select token_hash from saas_installation where installation_id=?",String.class,request.installationId());
        jdbc.update("""
            insert into saas_disaster_recovery(id,ticket_id,installation_id,backup_id,state,destination_public_key,
                destination_key_fingerprint,backup_sha256,source_token_hash,expires_at,created_by,note,backup_descriptor)
            values (?,?,?,?,'DOWNLOAD_AUTHORIZED',?,?,?,?,?,?,?,?::jsonb)
            """,request.requestId(),ticket,request.installationId(),request.backupId(),request.destinationPublicKey(),fingerprint,
                descriptor.sha256(),sourceHash,Timestamp.from(clock.instant().plus(Duration.ofHours(24))),audit.currentUsername(),request.note(),mapper.valueToTree(descriptor).toString());
        vault.pin(request.installationId(),request.backupId(),request.requestId());
        audit.log("DISASTER_RECOVERY_AUTHORIZED","SUPPORT_TICKET",ticket.toString(),request.requestId().toString());
        return state(required(request.requestId()));
    }
    public State adminState(UUID ticket,UUID id) { var row=locked(id); requireTicket(ticket,row); return state(row); }
    public List<CloudBackupDescriptor> backups(UUID ticket,UUID installationId) { var installation=lock(installationId);ticket(ticket,installation);return vault.listAvailable(installationId); }
    public InputStream downloadBackup(UUID ticket,UUID installationId,UUID backupId)throws Exception {ticket(ticket,lock(installationId));return vault.openVerified(installationId,backupId);}
    public byte[] downloadBackupKey(UUID ticket,UUID installationId,UUID backupId){ticket(ticket,lock(installationId));return vault.wrappedRecoveryKey(installationId,backupId);}
    public List<Map<String,Object>> ticketInstallations(UUID ticket) {
        if(jdbc.queryForObject("select count(*) from saas_support_ticket where id=? and status in ('ABIERTO','EN_CURSO')",Long.class,ticket)!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"RECOVERY_TICKET_NOT_FOUND");
        return jdbc.queryForList("select i.installation_id as \"installationId\",i.installation_reference as \"reference\",s.name as \"storeName\" from saas_installation i join saas_store s on s.id=i.store_id join saas_support_ticket t on t.company_id=i.company_id where t.id=? and i.active=true order by s.name,i.installation_id",ticket);
    }
    public State inspect(UUID id,Proof proof) {var row=locked(id);proof(row,"inspect","",proof.destinationProof(),true);return state(row);}

    /** Read-only entitlement recovery. Neither a consumed grant nor its credential is renewed here. */
    SaasInstallation requireActivatedLicenseCredential(UUID id, Proof request, String token) {
        var row=locked(id);
        proof(row,"license","",request.destinationProof(),true);
        if(!"ACTIVATED".equals(row.state()))throw conflict("RECOVERY_NOT_ACTIVATED");
        var installation=lock(row.installation());
        String issued=jdbc.queryForObject("select activated_token_hash from saas_disaster_recovery where id=?",String.class,id);
        if(!installation.isActive() || !installation.hasTokenHash(issued))throw conflict("RECOVERY_ACTIVATION_SUPERSEDED");
        if(token==null || token.isBlank() || !installation.hasTokenHash(tokens.hash(token)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"RECOVERY_CREDENTIAL_INVALID");
        return installation;
    }

    /** Keyset pages allow arbitrary installation histories without holding all payloads in memory. */
    public JsonNode evidence(UUID id,UUID after,Proof proof) {
        var row=locked(id);proof(row,"evidence",after==null?"":after.toString(),proof.destinationProof(),false);
        var installation=lock(row.installation());var result=checkpoint(installation);
        var list=jdbc.query("""
                select event_id,payload_hash,entity_type,entity_id,operation,store_sequence from saas_sync_event
                where installation_id=? and (?::uuid is null or event_id>?::uuid) order by event_id limit 501
                """,(rs,n)->{
                    var item=mapper.createObjectNode();item.put("eventId",rs.getString(1));item.put("payloadHash",rs.getString(2));
                    item.put("entityType",rs.getString(3));item.put("entityId",rs.getString(4));item.put("operation",rs.getString(5));
                    if(rs.getObject(6)!=null)item.put("storeSequence",rs.getLong(6));return item;
                },installation.getId(),after,after);
        var entries=result.putArray("events");list.stream().limit(500).forEach(entries::add);
        if(list.size()>500)result.put("afterId",list.get(499).path("eventId").asText());
        return result;
    }
    public State received(UUID id,Received request) {
        var row=locked(id);String hash=hash(request.report());proof(row,"received",hash,request.destinationProof(),false);
        if(!Set.of("DOWNLOAD_AUTHORIZED","SUPPORT_REVIEW","ACTIVATION_APPROVED").contains(row.state()))throw conflict("RECOVERY_STATE_CONFLICT");
        if(request.report().toString().length()>128*1024)throw conflict("RECOVERY_REPORT_TOO_LARGE");
        if(!row.digest().equals(request.report().path("backupSha256").asText()))throw conflict("RECOVERY_ARTIFACT_MISMATCH");
        // A retried receipt must not revoke a support approval for the same evidence.
        // The activation path independently checks that the central checkpoint is still current.
        if(hash.equals(row.reportHash()))return state(row);
        jdbc.update("update saas_disaster_recovery set state='SUPPORT_REVIEW',approved_checkpoint=null,approved_by=null,approved_at=null,report=?::jsonb,report_hash=?,version=version+1 where id=?",
                request.report().toString(),hash,id);
        return state(required(id));
    }
    public State approve(UUID ticket,UUID id,Review review) {
        var row=locked(id);requireTicket(ticket,row);ticket(ticket,lock(row.installation()));requireVersion(row,review);
        if(!"SUPPORT_REVIEW".equals(row.state()) || !review.sourceRetiredConfirmed())throw conflict("RECOVERY_REVIEW_REQUIRED");
        var current=state(row);
        if(!current.blockingReasons().isEmpty())throw conflict("RECOVERY_UNRESOLVED_EVIDENCE");
        jdbc.update("update saas_disaster_recovery set state='ACTIVATION_APPROVED',expires_at=now()+interval '24 hours',approved_checkpoint=?,approved_by=?,approved_at=now(),note=?,version=version+1 where id=?",
                current.evidence().path("checkpoint").asText(),audit.currentUsername(),review.note(),id);
        audit.log("DISASTER_RECOVERY_APPROVED","SUPPORT_TICKET",ticket.toString(),id.toString());
        return state(required(id));
    }
    public State cancel(UUID ticket,UUID id,Review review) {
        var row=locked(id);requireTicket(ticket,row);
        if("CANCELLED".equals(row.state()))return state(row);
        requireVersion(row,review);if("ACTIVATED".equals(row.state()))throw conflict("RECOVERY_ALREADY_ACTIVATED");
        jdbc.update("update saas_disaster_recovery set state='CANCELLED',version=version+1,note=? where id=?",review.note(),id);
        vault.unpin(row.installation(),row.backup(),id);audit.log("DISASTER_RECOVERY_CANCELLED","SUPPORT_TICKET",ticket.toString(),id.toString());
        return state(required(id));
    }
    public State renew(UUID ticket,UUID id,Review review) {
        var row=locked(id);requireTicket(ticket,row);ticket(ticket,lock(row.installation()));requireVersion(row,review);
        if(Set.of("ACTIVATED","CANCELLED").contains(row.state()))throw conflict("RECOVERY_GRANT_CLOSED");
        // Renew access to this same key/artifact only; an old approval must be reviewed again.
        jdbc.update("update saas_disaster_recovery set expires_at=?,state=case when report is null then 'DOWNLOAD_AUTHORIZED' else 'SUPPORT_REVIEW' end,approved_checkpoint=null,approved_by=null,approved_at=null,note=?,version=version+1 where id=?",
                Timestamp.from(clock.instant().plus(Duration.ofHours(24))),review.note(),id);
        audit.log("DISASTER_RECOVERY_REAUTHORIZED","SUPPORT_TICKET",ticket.toString(),id.toString());
        return state(required(id));
    }
    public Activation activate(UUID id,Proof request) {
        var row=locked(id);proof(row,"activate","",request.destinationProof(),true);var installation=lock(row.installation());
        if("ACTIVATED".equals(row.state())) {
            var issued=jdbc.queryForObject("select activated_token_hash from saas_disaster_recovery where id=?",String.class,id);
            if(!installation.hasTokenHash(issued))throw conflict("RECOVERY_ACTIVATION_SUPERSEDED");
            return activation(row);
        }
        if(!"ACTIVATION_APPROVED".equals(row.state()))throw conflict("RECOVERY_SUPPORT_APPROVAL_REQUIRED");
        var state=state(row);String approved=jdbc.queryForObject("select approved_checkpoint from saas_disaster_recovery where id=?",String.class,id);
        if(!state.blockingReasons().isEmpty() || !Objects.equals(approved,state.evidence().path("checkpoint").asText()))
            throw conflict("RECOVERY_EVIDENCE_CHANGED");
        if(!installation.hasTokenHash(row.sourceHash()))throw conflict("RECOVERY_SOURCE_SUPERSEDED");
        String token=tokens.newToken();String encrypted=RecoveryCryptography.encrypt(row.key(),token);
        installation.rotateTokenHash(tokens.hash(token));installation.invalidateLinkRecovery();installations.saveAndFlush(installation);
        jdbc.update("update saas_server_transfer set state='CANCELLED',cancelled_at=now() where installation_id=? and state in ('PREPARED','SEALED')",row.installation());
        long floor=vault.maxSequenceNumber(row.installation());
        jdbc.update("update saas_disaster_recovery set state='ACTIVATED',encrypted_credential=?,activated_token_hash=?,activated_at=now(),sequence_floor=?,version=version+1 where id=?",encrypted,tokens.hash(token),floor,id);
        audit.log("DISASTER_RECOVERY_ACTIVATED","SUPPORT_TICKET",row.ticket().toString(),id.toString());
        // Retain the pin until destination bootstrap acknowledges activation; a lost ACK must remain recoverable.
        return activation(required(id));
    }
    public State acknowledge(UUID id,Proof request) {
        var row=locked(id);proof(row,"acknowledge","",request.destinationProof(),true);
        if(!"ACTIVATED".equals(row.state()))throw conflict("RECOVERY_NOT_ACTIVATED");
        vault.unpin(row.installation(),row.backup(),id);return state(row);
    }
    public InputStream destinationArtifact(UUID id,Proof proof) throws Exception {var row=locked(id);proof(row,"artifact","",proof.destinationProof(),false);return vault.openVerified(row.installation(),row.backup());}
    public byte[] destinationKey(UUID id,Proof proof) {var row=locked(id);proof(row,"wrapped-key","",proof.destinationProof(),false);return vault.wrappedRecoveryKey(row.installation(),row.backup());}
    public InputStream adminArtifact(UUID ticket,UUID id) throws Exception {var row=locked(id);requireTicket(ticket,row);return vault.openVerified(row.installation(),row.backup());}
    public byte[] adminKey(UUID ticket,UUID id) {var row=locked(id);requireTicket(ticket,row);return vault.wrappedRecoveryKey(row.installation(),row.backup());}
    private Activation activation(Row row) {return new Activation(state(row),jdbc.queryForObject("select encrypted_credential from saas_disaster_recovery where id=?",String.class,row.id()));}
    private State state(Row row) {
        var installation=lock(row.installation());var evidence=checkpoint(installation);var blockers=new ArrayList<String>();
        if(!installation.isActive())blockers.add("INSTALLATION_REVOKED");
        if(installation.getLicense().getStatus()!=LicenseSaasStatus.VALIDA || !clock.instant().isBefore(installation.getLicense().getValidUntil()))blockers.add("LICENSE_NOT_VALID");
        if(!evidence.path("projectionLedgerComplete").asBoolean())blockers.add("CENTRAL_PROJECTION_EVIDENCE_INCOMPLETE");
        if(evidence.path("projectionErrors").asLong()>0)blockers.add("CENTRAL_PROJECTION_ERRORS_UNRESOLVED");
        JsonNode report=row.report();
        if(report==null)blockers.add("DESTINATION_NOT_RECEIVED");
        else {
            if(!report.path("snapshotVerified").asBoolean() || !report.path("localSafetyReady").asBoolean())blockers.add("DESTINATION_VALIDATION_INCOMPLETE");
            if(!report.path("portableSecretsComplete").asBoolean())blockers.add("PORTABLE_SECRETS_INCOMPLETE");
            if(report.path("unresolvedPayments").asLong(-1)!=0)blockers.add("PAYMENTS_UNRESOLVED");
            if(!report.path("centralMatches").asBoolean() || !evidence.path("checkpoint").asText().equals(report.path("centralCheckpoint").asText()))blockers.add("CENTRAL_AHEAD_OR_CHANGED");
            if(report.path("centralEventCount").asLong(-1)!=evidence.path("eventCount").asLong())blockers.add("CENTRAL_EVIDENCE_INCOMPLETE");
        }
        CloudBackupDescriptor descriptor;
        try {descriptor=mapper.treeToValue(json(jdbc.queryForObject("select backup_descriptor::text from saas_disaster_recovery where id=?",String.class,row.id())),CloudBackupDescriptor.class);}
        catch(Exception invalid){throw new IllegalStateException("Recovery descriptor invalid",invalid);}
        return new State(row.id(),row.ticket(),row.installation(),row.backup(),row.state(),row.version(),row.fingerprint(),row.digest(),row.expires(),List.copyOf(blockers),row.floor(),descriptor,evidence,List.of("UNSYNCED_POST_BACKUP_DATA_MAY_BE_LOST","EXTERNAL_FISCAL_AND_SHARED_DATA_REVIEW_REQUIRED"));
    }
    private com.fasterxml.jackson.databind.node.ObjectNode checkpoint(SaasInstallation installation) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");long[] count={0};
            jdbc.query("select event_id,payload_hash,projection_status from saas_sync_event where installation_id=? order by event_id",rs->{
                count[0]++;digest.update((rs.getString(1)+"|"+rs.getString(2)+"|"+rs.getString(3)+"\n").getBytes(StandardCharsets.UTF_8));
            },installation.getId());
            // Include central fiscal policy/projection and commercial headers in the approval checkpoint.
            jdbc.query("select row_to_json(f)::text from saas_fiscal_status f where installation_id=? order by id",(org.springframework.jdbc.core.RowCallbackHandler)rs->digest.update(rs.getString(1).getBytes(StandardCharsets.UTF_8)),installation.getId());
            jdbc.query("select source_document_id,source_revision,source_event_id from saas_commercial_document where source_installation_id=? order by source_document_id",(org.springframework.jdbc.core.RowCallbackHandler)rs->digest.update((rs.getString(1)+"|"+rs.getLong(2)+"|"+rs.getString(3)+"\n").getBytes(StandardCharsets.UTF_8)),installation.getId());
            boolean projectionsComplete=jdbc.queryForObject("""
                select not exists(select 1 from saas_commercial_document d where d.source_installation_id=? and not exists(
                    select 1 from saas_sync_event e where e.event_id=d.source_event_id and e.installation_id=d.source_installation_id
                    and e.entity_type='DOCUMENTO' and e.entity_id=d.source_document_id and e.payload_hash=d.source_payload_hash))
                and not exists(select 1 from saas_fiscal_status f where f.installation_id=? and not exists(
                    select 1 from saas_sync_event e where e.installation_id=f.installation_id and e.entity_type='FISCAL_STATUS' and e.entity_id=f.entity_id and e.payload_hash=f.payload_hash))
                """,Boolean.class,installation.getId(),installation.getId());
            long projectionErrors=jdbc.queryForObject("select count(*) from saas_sync_event where installation_id=? and projection_status='ERROR'",Long.class,installation.getId());
            return mapper.createObjectNode().put("checkpoint",HexFormat.of().formatHex(digest.digest())).put("eventCount",count[0]).put("projectionLedgerComplete",projectionsComplete).put("projectionErrors",projectionErrors);
        } catch(java.security.GeneralSecurityException invalid){throw new IllegalStateException(invalid);}
    }
    private void proof(Row row,String action,String payloadHash,String signature,boolean activatedAllowed) {
        if("CANCELLED".equals(row.state()) || (!activatedAllowed && "ACTIVATED".equals(row.state())))throw conflict("RECOVERY_GRANT_CLOSED");
        if(!"ACTIVATED".equals(row.state()) && !clock.instant().isBefore(row.expires()))throw conflict("RECOVERY_GRANT_EXPIRED");
        if(!RecoveryCryptography.verify(row.key(),"TPV-RECOVERY-V1\n"+row.id()+"\n"+action+"\n"+("inspect".equals(action)?"":row.digest())+"\n"+payloadHash,signature))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"RECOVERY_DESTINATION_PROOF_INVALID");
    }
    private void ticket(UUID ticket,SaasInstallation installation) {
        // Keep the ticket open and in the same company until the authorization commits.
        if(jdbc.queryForList("select id from saas_support_ticket where id=? and company_id=? and status in ('ABIERTO','EN_CURSO') for share",UUID.class,ticket,installation.getCompany().getId()).size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"RECOVERY_TICKET_NOT_FOUND");
    }
    private void requireTicket(UUID ticket,Row row) {if(!row.ticket().equals(ticket))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"RECOVERY_NOT_FOUND");}
    private void requireVersion(Row row,Review request) {if(row.version()!=request.expectedVersion())throw conflict("RECOVERY_VERSION_CONFLICT");}
    private SaasInstallation lock(UUID installation) {return installations.findByInstallationIdForUpdate(installation).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"RECOVERY_INSTALLATION_NOT_FOUND"));}
    private Row locked(UUID id) {var row=required(id);lock(row.installation());return required(id);}
    private Row required(UUID id) {var row=find(id);if(row==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"RECOVERY_NOT_FOUND");return row;}
    private Row find(UUID id) {return jdbc.query("select * from saas_disaster_recovery where id=?",(rs,n)->new Row(rs.getObject("id",UUID.class),rs.getObject("ticket_id",UUID.class),rs.getObject("installation_id",UUID.class),rs.getObject("backup_id",UUID.class),rs.getString("state"),rs.getLong("version"),rs.getString("destination_public_key"),rs.getString("destination_key_fingerprint"),rs.getString("backup_sha256"),rs.getString("source_token_hash"),rs.getTimestamp("expires_at").toInstant(),json(rs.getString("report")),rs.getString("report_hash"),rs.getLong("sequence_floor")),id).stream().findFirst().orElse(null);}
    private JsonNode json(String value) {try{return value==null?null:mapper.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static String hash(JsonNode value) {return RecoveryCryptography.hash(value.toString().getBytes(StandardCharsets.UTF_8));}
    private static ResponseStatusException conflict(String code) {return new ResponseStatusException(HttpStatus.CONFLICT,code);}
    private record Row(UUID id,UUID ticket,UUID installation,UUID backup,String state,long version,String key,String fingerprint,String digest,String sourceHash,Instant expires,JsonNode report,String reportHash,long floor) { }
}

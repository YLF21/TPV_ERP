package com.tpverp.backend.servertransfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.installation.InstallationRepository;
import java.nio.file.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ServerTransferService {
    private final JdbcTemplate jdbc; private final TransactionTemplate transactions;
    private final InstallationRepository installations; private final ServerTransferJournal journal;
    private final ServerTransferFence fence; private final ServerTransferSaasClient central;
    private final ServerTransferBackupPort backups; private final ServerTransferSecrets secrets;
    private final ObjectMapper mapper;
    public ServerTransferService(JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager manager,
            InstallationRepository installations,ServerTransferJournal journal,ServerTransferFence fence,
            ServerTransferSaasClient central,ServerTransferBackupPort backups,ServerTransferSecrets secrets,ObjectMapper mapper) {
        this.jdbc=jdbc;this.transactions=new TransactionTemplate(manager);this.installations=installations;this.journal=journal;
        this.fence=fence;this.central=central;this.backups=backups;this.secrets=secrets;this.mapper=mapper;
    }
    public synchronized Status prepare(String destinationPublicKey,String destinationProof) {
        if(journal.frozen()) throw conflict("TRANSFER_ALREADY_FROZEN");
        try{var readiness=DestinationPreflight.requireCompatible(destinationPublicKey,destinationProof);secrets.preflightTls(readiness.publicHost(),readiness.tlsReady());}
        catch(Exception e){throw conflict("TRANSFER_DESTINATION_PREFLIGHT_INVALID");}
        secrets.preflight();
        UUID installation=installations.findAll().stream().findFirst().orElseThrow().getId();
        String fingerprint;
        try {fingerprint=TransferEnvelope.fingerprint(TransferEnvelope.publicKey(destinationPublicKey));}
        catch(Exception e) {throw conflict("INVALID_DESTINATION_KEY");}
        var current=current();
        boolean completed=current==null || Set.of("CANCELLED","ACTIVE").contains(current.state());
        if(!completed && !current.destinationKeyFingerprint().equals(fingerprint)) throw conflict("TRANSFER_ALREADY_PENDING");
        UUID id=completed?UUID.randomUUID():current.transferId();
        if(completed) jdbc.update("insert into server_transfer(id,installation_id,state,destination_public_key,destination_key_fingerprint) values (?,?,'PREPARED',?,?)",id,installation,destinationPublicKey,fingerprint);
        var confirmed=central.prepare(id,installation,destinationPublicKey,destinationProof);
        requireProof(confirmed,id,installation,"PREPARED");
        if(!fingerprint.equals(confirmed.destinationKeyFingerprint())) throw conflict("TRANSFER_DESTINATION_MISMATCH");
        return status();
    }
    public synchronized Status seal(UUID id) {
        var current=required(id);
        if("SEALED".equals(current.state())) return current;
        if(!Set.of("PREPARED","FROZEN").contains(current.state())) throw conflict("TRANSFER_STATE_CONFLICT");
        // Revalidate credentials and availability before the irreversible local fence.
        var centralState=central.status(id,current.installationId());
        if(!Set.of("PREPARED","SEALED").contains(centralState.state())) throw conflict("TRANSFER_STATE_CONFLICT");
        fence.exclusive(()->{
            secrets.preflight();
            if(jdbc.queryForObject("select count(*) from payment_terminal_operation where status in ('PENDING','SENT','TIMEOUT','REVIEW_REQUIRED')",Long.class)>0)
                throw conflict("TRANSFER_PAYMENTS_UNRESOLVED");
            if(jdbc.queryForObject("select count(*) from sesion s join terminal t on t.id=s.terminal_id where s.revocada_en is null and t.workstation_code <> '001'",Long.class)>0)
                throw conflict("TRANSFER_CLOSE_OTHER_TERMINALS");
            central.preserveSourceCancellationCredential(id,current.installationId());
            journal.freeze(id);
            transactions.executeWithoutResult(tx->{
                jdbc.execute("select pg_advisory_xact_lock(774151001)");
                jdbc.update("update server_transfer set state='FROZEN' where id=? and state='PREPARED'",id);
            });
        });
        var row=jdbc.queryForMap("select * from server_transfer where id=?",id);
        Path artifact;
        String digest;
        if(row.get("backup_path")==null) {
            var backup=backups.createFinalBackup(id,current.installationId(),current.destinationKeyFingerprint());
            artifact=backup.artifactPath();digest=backup.sha256();
            jdbc.update("update server_transfer set backup_path=?,backup_sha256=? where id=?",artifact.toAbsolutePath().toString(),digest,id);
        } else { artifact=Path.of((String)row.get("backup_path"));digest=(String)row.get("backup_sha256"); }
        // The final local snapshot remains journaled if cloud delivery fails; retry uses this same file.
        backups.ensureCloudCopy(artifact);
        String capsule=(String)row.get("capsule_sha256");
        if(capsule==null) {
            capsule=secrets.write(id,current.installationId(),(String)row.get("destination_public_key"),artifact);
            jdbc.update("update server_transfer set capsule_sha256=? where id=?",capsule,id);
        }
        var confirmation=central.seal(id,current.installationId(),digest,capsule);
        requireProof(confirmation,id,current.installationId(),"SEALED");
        if(!digest.equals(confirmation.backupSha256()) || !capsule.equals(confirmation.capsuleSha256())) throw conflict("TRANSFER_SEAL_MISMATCH");
        try { TransferSecretHandoff.writeFile(artifact.resolveSibling(artifact.getFileName()+".transfer.json"),mapper.writeValueAsBytes(confirmation)); }
        catch(Exception e) {throw new IllegalStateException("TRANSFER_MANIFEST_WRITE_FAILED",e);}
        jdbc.update("update server_transfer set state='SEALED' where id=?",id);
        return status();
    }
    public synchronized Status cancel(UUID id) {
        var value=required(id);
        var confirmation="PREPARED".equals(value.state())
                ?central.cancel(id,value.installationId(),jdbc.queryForObject("select destination_public_key from server_transfer where id=?",String.class,id))
                :central.cancel(id,value.installationId());
        requireProof(confirmation,id,value.installationId(),"CANCELLED");
        if(!value.destinationKeyFingerprint().equals(confirmation.destinationKeyFingerprint()))throw conflict("TRANSFER_DESTINATION_MISMATCH");
        jdbc.update("update server_transfer set state='CANCELLED' where id=?",id);
        journal.releaseAfterCentralCancellation(id);
        central.clearSourceCancellationCredential();
        return status();
    }
    public synchronized Status status() {
        var value=current();
        if(value!=null && "SEALED".equals(value.state())) {
            try {
                var remote=central.status(value.transferId(),value.installationId());
                if(value.transferId().equals(remote.transferId()) && value.installationId().equals(remote.installationId()) && "ACTIVATED".equals(remote.state())) {
                    jdbc.update("update server_transfer set state='ACTIVATED' where id=?",value.transferId());value=current();
                }
            } catch(RuntimeException unavailable) { /* Connectivity never reopens the source. */ }
        }
        return value==null?new Status(null,null,"NONE",null,null):value;
    }
    private Status current() {
        return jdbc.query("select * from server_transfer order by created_at desc limit 1",(rs,row)->new Status(rs.getObject("id",UUID.class),rs.getObject("installation_id",UUID.class),rs.getString("state"),rs.getString("destination_key_fingerprint"),rs.getString("backup_sha256"))).stream().findFirst().orElse(null);
    }
    private Status required(UUID id) {var value=current();if(value==null || !id.equals(value.transferId()))throw conflict("TRANSFER_NOT_FOUND");return value;}
    private static void requireProof(ServerTransferSaasClient.Status status,UUID id,UUID installation,String state) {
        if(status==null || !id.equals(status.transferId()) || !installation.equals(status.installationId()) || !state.equals(status.state())) throw conflict("TRANSFER_CENTRAL_PROOF_INVALID");
    }
    private static ResponseStatusException conflict(String code) {return new ResponseStatusException(HttpStatus.CONFLICT,code);}
    public record Status(UUID transferId,UUID installationId,String state,String destinationKeyFingerprint,String backupSha256) {}
}

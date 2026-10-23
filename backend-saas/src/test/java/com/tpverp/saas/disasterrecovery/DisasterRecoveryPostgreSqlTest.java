package com.tpverp.saas.disasterrecovery;

import static org.assertj.core.api.Assertions.*;
import static com.tpverp.saas.SaasTestData.validCif;
import static com.tpverp.saas.disasterrecovery.DisasterRecoveryModels.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.*;
import com.tpverp.saas.backup.*;
import com.tpverp.saas.license.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties="tpv.saas.backup-vault.directory=target/test-disaster-vault")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DisasterRecoveryPostgreSqlTest {
    private static final String SCHEMA="disaster_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void schema(DynamicPropertyRegistry values) {
        values.add("spring.flyway.default-schema",()->SCHEMA);values.add("spring.datasource.hikari.schema",()->SCHEMA);values.add("spring.jpa.properties.hibernate.default_schema",()->SCHEMA);
    }
    @Autowired DisasterRecoveryService service;
    @Autowired CloudBackupVaultService vault;
    @Autowired SaasCompanyRepository companies;
    @Autowired SaasStoreRepository stores;
    @Autowired SaasLicenseRepository licenses;
    @Autowired SaasInstallationRepository installations;
    @Autowired TokenHasher tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired InstallationAuthenticator authenticator;
    @Autowired LicenseValidationService validation;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    static KeyPair destination;
    @BeforeAll static void key()throws Exception {var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);destination=generator.generateKeyPair();}

    @Test void supportApprovalIsRequiredAndActivationIsOneUseWithLostAckReplay()throws Exception {
        var site=site();var state=authorize(site);
        assertThatThrownBy(()->service.activate(state.recoveryId(),proof(state,"activate",""))).hasMessageContaining("SUPPORT_APPROVAL_REQUIRED");
        var received=receive(state,true);
        var approved=service.approve(site.ticket(),state.recoveryId(),new Review(received.version(),"Validated destination and retired source",true));
        assertThat(approved.state()).isEqualTo("ACTIVATION_APPROVED");
        var activated=service.activate(state.recoveryId(),proof(state,"activate",""));
        assertThat(service.activate(state.recoveryId(),proof(state,"activate",""))).isEqualTo(activated);
        assertThat(installations.findByInstallationId(site.logical()).orElseThrow().hasTokenHash(tokens.hash(site.token()))).isFalse();
        assertThatThrownBy(()->vault.available(site.logical(),site.token())).hasMessageContaining("401");
        assertThatThrownBy(()->service.cancel(site.ticket(),state.recoveryId(),new Review(activated.status().version(),"Undo",true))).hasMessageContaining("ALREADY_ACTIVATED");
        service.acknowledge(state.recoveryId(),proof(state,"acknowledge",""));
        assertThat(service.activate(state.recoveryId(),proof(state,"activate",""))).isEqualTo(activated);
    }
    @Test void mvcConverterAcceptsReceiptAndReturnsEvidenceAsJsonObject()throws Exception {
        var site=site();var state=authorize(site);
        var evidence=service.evidence(state.recoveryId(),null,proof(state,"evidence",""));
        var report=mapper.createObjectNode().put("backupSha256",state.backupSha256()).put("snapshotVerified",true)
                .put("portableSecretsComplete",true).put("localSafetyReady",true).put("unresolvedPayments",0)
                .put("centralCheckpoint",evidence.path("checkpoint").asText())
                .put("centralEventCount",evidence.path("eventCount").asLong()).put("centralMatches",true);
        var signature=proof(state,"received",RecoveryCryptography.hash(report.toString().getBytes(StandardCharsets.UTF_8)));
        mvc.perform(post("/api/v1/backup-recoveries/"+state.recoveryId()+"/received")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new Received(signature.destinationProof(),report))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUPPORT_REVIEW"))
                .andExpect(jsonPath("$.evidence.checkpoint").value(evidence.path("checkpoint").asText()))
                .andExpect(jsonPath("$.evidence.eventCount").value(0));
        mvc.perform(post("/api/v1/backup-recoveries/"+state.recoveryId()+"/evidence")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(proof(state,"evidence",""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoint").value(evidence.path("checkpoint").asText()));
    }
    @Test void centralAdvanceOrUnresolvedEvidenceCannotBeBypassedBySupport()throws Exception {
        var site=site();var state=authorize(site);var incomplete=receive(state,false);
        assertThatThrownBy(()->service.approve(site.ticket(),state.recoveryId(),new Review(incomplete.version(),"I reviewed it",true))).hasMessageContaining("UNRESOLVED_EVIDENCE");
        var ready=receive(state,true);service.approve(site.ticket(),state.recoveryId(),new Review(ready.version(),"Ready",true));
        jdbc.update("insert into saas_sync_event(event_id,company_id,store_id,installation_id,entity_type,entity_id,operation,payload,payload_hash,schema_version,projection_status,received_at) values (?,?,?,?,? ,?,'ACTUALIZAR','{}',?,1,'RECEIVED',now())",
                UUID.randomUUID(),site.company(),site.store(),site.central(),"PRODUCTO",UUID.randomUUID(),"a".repeat(64));
        assertThatThrownBy(()->service.activate(state.recoveryId(),proof(state,"activate",""))).hasMessageContaining("EVIDENCE_CHANGED");
        assertThat(installations.findByInstallationId(site.logical()).orElseThrow().hasTokenHash(tokens.hash(site.token()))).isTrue();
    }
    @Test void retryingIdenticalReceiptPreservesApprovalButChangedEvidenceRequiresReview()throws Exception {
        var site=site();var state=authorize(site);var received=receive(state,true);
        var approved=service.approve(site.ticket(),state.recoveryId(),new Review(received.version(),"Validated destination",true));
        var replay=receive(state,true);
        assertThat(replay.state()).isEqualTo("ACTIVATION_APPROVED");
        assertThat(replay.version()).isEqualTo(approved.version());
        assertThat(jdbc.queryForObject("select approved_by is not null and approved_checkpoint is not null from saas_disaster_recovery where id=?",Boolean.class,state.recoveryId())).isTrue();
        var changed=receive(state,false);
        assertThat(changed.state()).isEqualTo("SUPPORT_REVIEW");
        assertThat(changed.version()).isGreaterThan(approved.version());
        assertThatThrownBy(()->service.activate(state.recoveryId(),proof(state,"activate",""))).hasMessageContaining("SUPPORT_APPROVAL_REQUIRED");
    }
    @Test void ticketTenantKeyAndReadPermissionsAreEnforced()throws Exception {
        var first=site();var second=site();var state=authorize(first);
        assertThatThrownBy(()->service.adminState(second.ticket(),state.recoveryId())).hasMessageContaining("404");
        assertThatThrownBy(()->service.backups(second.ticket(),first.logical())).hasMessageContaining("404");
        assertThatThrownBy(()->service.inspect(state.recoveryId(),new Proof(Base64.getEncoder().encodeToString(new byte[384])))).hasMessageContaining("PROOF_INVALID");
        String path="/api/v1/admin/tickets/"+first.ticket()+"/backup-recoveries/"+state.recoveryId()+"/wrapped-key";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization",basic("viewer"))).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization",basic("admin"))).andExpect(status().isOk());
    }
    @Test void cancelledGrantCannotDownloadOrActivate()throws Exception {
        var site=site();var state=authorize(site);service.cancel(site.ticket(),state.recoveryId(),new Review(state.version(),"Cancelled",false));
        assertThatThrownBy(()->service.destinationKey(state.recoveryId(),proof(state,"wrapped-key",""))).hasMessageContaining("GRANT_CLOSED");
        assertThatThrownBy(()->service.activate(state.recoveryId(),proof(state,"activate",""))).hasMessageContaining("GRANT_CLOSED");
    }
    @Test void closedTicketCannotAuthorizeActivationButCanStillCancelPendingRecovery()throws Exception {
        var site=site();var state=authorize(site);var received=receive(state,true);
        jdbc.update("update saas_support_ticket set status='RESUELTO' where id=?",site.ticket());
        assertThatThrownBy(()->service.approve(site.ticket(),state.recoveryId(),new Review(received.version(),"Approve after closure",true)))
                .hasMessageContaining("RECOVERY_TICKET_NOT_FOUND");
        assertThat(service.adminState(site.ticket(),state.recoveryId()).state()).isEqualTo("SUPPORT_REVIEW");
        assertThat(service.cancel(site.ticket(),state.recoveryId(),new Review(received.version(),"Cancel closed ticket recovery",false)).state()).isEqualTo("CANCELLED");
    }
    @Test void projectionWithoutVerifiableLedgerBlocksEvenAfterMatchingReceipt()throws Exception {
        var site=site();var state=authorize(site);
        jdbc.update("""
            insert into saas_fiscal_status(id,installation_id,company_id,store_id,source_installation_id,entity_id,
            effective_mode,activation_state,mode_version,runtime_class,endpoint_environment,transport_mode,reported_at,received_at,payload_hash)
            values(?,?,?,?,?,?,'PRE_SIF','ACTIVE',0,'SANDBOX','TEST','SIMULATED',now(),now(),?)
            """,UUID.randomUUID(),site.central(),site.company(),site.store(),site.logical(),UUID.randomUUID(),"b".repeat(64));
        var received=receive(state,true);
        assertThat(received.blockingReasons()).contains("CENTRAL_PROJECTION_EVIDENCE_INCOMPLETE");
        assertThatThrownBy(()->service.approve(site.ticket(),state.recoveryId(),new Review(received.version(),"Externally reviewed",true))).hasMessageContaining("UNRESOLVED_EVIDENCE");
    }
    @Test void expiredGrantRequiresSupportRenewalAndApprovalDoesNotSurviveRenewal()throws Exception {
        var site=site();var state=authorize(site);var received=receive(state,true);
        var approved=service.approve(site.ticket(),state.recoveryId(),new Review(received.version(),"Reviewed",true));
        jdbc.update("update saas_disaster_recovery set expires_at=now()-interval '1 minute' where id=?",state.recoveryId());
        assertThatThrownBy(()->service.inspect(state.recoveryId(),proof(state,"inspect",""))).hasMessageContaining("GRANT_EXPIRED");
        var renewed=service.renew(site.ticket(),state.recoveryId(),new Review(approved.version(),"Continue with same destination after external review",true));
        assertThat(renewed.state()).isEqualTo("SUPPORT_REVIEW");
        assertThat(service.inspect(state.recoveryId(),proof(state,"inspect","")).destinationKeyFingerprint()).isEqualTo(state.destinationKeyFingerprint());
        assertThatThrownBy(()->service.activate(state.recoveryId(),proof(state,"activate",""))).hasMessageContaining("SUPPORT_APPROVAL_REQUIRED");
    }
    @Test void readonlyAuthenticationChecksLiveCredentialWithoutPostgresqlRowLock()throws Exception {
        var site=site();var stale=installations.findByInstallationId(site.logical()).orElseThrow();
        var read=new org.springframework.transaction.support.TransactionTemplate(transactionManager);read.setReadOnly(true);
        read.executeWithoutResult(tx->{
            assertThat(jdbc.queryForObject("show transaction_read_only",String.class)).isEqualTo("on");
            authenticator.requireToken(stale,site.token());
        });
        jdbc.update("update saas_installation set token_hash=? where id=?",tokens.hash(tokens.newToken()),site.central());
        assertThatThrownBy(()->read.executeWithoutResult(tx->authenticator.requireToken(stale,site.token())))
                .hasMessageContaining("401");
    }
    @Test void concurrentLicenseValidationDoesNotUpgradeTwoSharedLocks()throws Exception {
        var site=site();String reference=jdbc.queryForObject("select l.reference from saas_license l join saas_installation i on i.license_id=l.id where i.id=?",String.class,site.central());
        var request=new LicenseSaasValidationRequest(site.logical(),"RECOVERY-TEST",site.store(),reference,null);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{start.await();return validation.validate(request,site.token());});
            var second=pool.submit(()->{start.await();return validation.validate(request,site.token());});
            start.countDown();
            assertThat(first.get(15,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(second.get(15,java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    private State receive(State state,boolean matches)throws Exception {
        var evidence=service.evidence(state.recoveryId(),null,proof(state,"evidence",""));
        var report=mapper.createObjectNode().put("backupSha256",state.backupSha256()).put("snapshotVerified",true).put("portableSecretsComplete",true)
                .put("localSafetyReady",true).put("unresolvedPayments",0).put("centralCheckpoint",evidence.path("checkpoint").asText())
                .put("centralEventCount",evidence.path("eventCount").asLong()).put("centralMatches",matches);
        return service.received(state.recoveryId(),new Received(proof(state,"received",RecoveryCryptography.hash(report.toString().getBytes(StandardCharsets.UTF_8))).destinationProof(),report));
    }
    private State authorize(Site site)throws Exception {
        byte[] data="encrypted-backup".getBytes(StandardCharsets.US_ASCII),wrapped=java.nio.ByteBuffer.allocate(91).put(new byte[]{'T','P','V','R'}).put((byte)2).putInt(600000).put((byte)16).put((byte)12).putInt(48).array();
        var descriptor=new CloudBackupDescriptor(UUID.randomUUID(),site.logical(),5,Instant.now(),"test",1,1,data.length,RecoveryCryptography.hash(data),RecoveryCryptography.hash(wrapped),2);
        vault.begin(descriptor,wrapped,site.token());vault.upload(site.logical(),descriptor.backupId(),site.token(),new ByteArrayInputStream(data));
        return service.create(site.ticket(),new Create(UUID.randomUUID(),site.logical(),descriptor.backupId(),Base64.getEncoder().encodeToString(destination.getPublic().getEncoded()),"PC failed, destination authorized"));
    }
    private Site site() {
        var company=companies.saveAndFlush(new SaasCompany(UUID.randomUUID(),"Recovery test",validCif("B"+java.util.concurrent.ThreadLocalRandom.current().nextInt(1000000,9999999)+"0"),TaxpayerType.SOCIEDAD,TaxRegime.IVA,Instant.now()));
        var store=stores.saveAndFlush(new SaasStore(UUID.randomUUID(),company,"001","Store","Atlantic/Canary",Instant.now()));
        var license=licenses.saveAndFlush(new SaasLicense(UUID.randomUUID(),company,"REC-"+UUID.randomUUID(),Instant.now().plusSeconds(86400),1,1,Instant.now()));
        String token=tokens.newToken();var installation=installations.saveAndFlush(new SaasInstallation(UUID.randomUUID(),company,store,license,UUID.randomUUID(),"RECOVERY-TEST",null,tokens.hash(token),Instant.now()));
        UUID ticket=UUID.randomUUID();jdbc.update("insert into saas_support_ticket(id,company_id,title,status,priority,created_by,created_at,updated_at) values (?,?,'Recovery','ABIERTO','NORMAL','admin',now(),now())",ticket,company.getId());
        return new Site(installation.getInstallationId(),installation.getId(),company.getId(),store.getId(),ticket,token);
    }
    private static Proof proof(State state,String action,String context)throws Exception {
        String text="TPV-RECOVERY-V1\n"+state.recoveryId()+"\n"+action+"\n"+(action.equals("inspect")?"":state.backupSha256())+"\n"+context;
        var signature=Signature.getInstance("SHA256withRSA");signature.initSign(destination.getPrivate());signature.update(text.getBytes(StandardCharsets.UTF_8));
        return new Proof(Base64.getEncoder().encodeToString(signature.sign()));
    }
    private static String basic(String user){return "Basic "+Base64.getEncoder().encodeToString((user+":admin").getBytes(StandardCharsets.UTF_8));}
    private record Site(UUID logical,UUID central,UUID company,UUID store,UUID ticket,String token) { }
}

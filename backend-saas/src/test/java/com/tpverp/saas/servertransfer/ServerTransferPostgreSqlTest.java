package com.tpverp.saas.servertransfer;

import static org.assertj.core.api.Assertions.*;
import static com.tpverp.saas.SaasTestData.validCif;
import com.tpverp.saas.license.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("test")
class ServerTransferPostgreSqlTest {
    private static final String SCHEMA="server_transfer_"+UUID.randomUUID().toString().replace("-","");
    @org.springframework.test.context.DynamicPropertySource
    static void schema(org.springframework.test.context.DynamicPropertyRegistry values) {
        values.add("spring.flyway.default-schema",()->SCHEMA);values.add("spring.datasource.hikari.schema",()->SCHEMA);values.add("spring.jpa.properties.hibernate.default_schema",()->SCHEMA);
    }
    static final AtomicInteger NUMBER=new AtomicInteger(9200000);
    static KeyPair destination;
    @Autowired ServerTransferService service;
    @Autowired SaasInstallationRepository installations;
    @Autowired SaasCompanyRepository companies;
    @Autowired SaasStoreRepository stores;
    @Autowired SaasLicenseRepository licenses;
    @Autowired TokenHasher tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @BeforeAll static void key()throws Exception {var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);destination=generator.generateKeyPair();}
    @Test void retryPreservesLogicalIdentityAndRotatesOnlyCredentials()throws Exception {
        var site=site();var request=prepare(site);var first=service.prepare(request,site.token());
        assertThat(service.prepare(request,site.token())).isEqualTo(first);
        var sealed=seal(site,request.transferId());assertThat(service.seal(request.transferId(),sealRequest(site),site.token())).isEqualTo(sealed);
        var proof=activate(site,request.transferId());var activated=service.activate(request.transferId(),proof);
        assertThat(service.activate(request.transferId(),proof)).isEqualTo(activated);
        var cipher=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.DECRYPT_MODE,destination.getPrivate(),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));
        String token=new String(cipher.doFinal(Base64.getDecoder().decode(activated.encryptedCredential())),StandardCharsets.UTF_8);
        var installation=installations.findByInstallationId(site.id()).orElseThrow();
        assertThat(installation.isActive()).isTrue();assertThat(installation.hasTokenHash(tokens.hash(token))).isTrue();
        assertThat(installation.hasTokenHash(tokens.hash(site.token()))).isFalse();assertThat(installation.getInstallationId()).isEqualTo(site.id());
        assertThat(installation.isCurrentPairing(UUID.randomUUID())).isFalse();
        assertThat(service.status(request.transferId(),site.id(),site.token()).state()).isEqualTo("ACTIVATED");
        assertThatThrownBy(()->service.cancel(request.transferId(),site.id(),site.token())).hasMessageContaining("401");
    }
    @Test void cancelledTransferCannotBeActivated()throws Exception {
        var site=site();var req=prepare(site);service.prepare(req,site.token());seal(site,req.transferId());
        assertThat(service.cancel(req.transferId(),site.id(),site.token()).state()).isEqualTo("CANCELLED");
        assertThatThrownBy(()->service.activate(req.transferId(),activate(site,req.transferId()))).hasMessageContaining("TRANSFER_STATE_CONFLICT");
    }
    @Test void invalidDestinationProofAndChangedDigestAreRejected()throws Exception {
        var site=site();var req=prepare(site);
        var wrong=new ServerTransferController.Prepare(req.transferId(),site.id(),req.destinationPublicKey(),Base64.getEncoder().encodeToString(new byte[384]));
        assertThatThrownBy(()->service.prepare(wrong,site.token())).hasMessageContaining("DESTINATION_PROOF_INVALID");
        service.prepare(req,site.token());seal(site,req.transferId());
        assertThatThrownBy(()->service.seal(req.transferId(),new ServerTransferController.Seal(site.id(),"c".repeat(64),"b".repeat(64)),site.token())).hasMessageContaining("TRANSFER_SEAL_CONFLICT");
    }
    @Test void retiredCredentialCannotSealOrCancel()throws Exception {
        var site=site();var req=prepare(site);service.prepare(req,site.token());rotate(site);
        assertThatThrownBy(()->service.seal(req.transferId(),sealRequest(site),site.token())).hasMessageContaining("401");
        assertThatThrownBy(()->service.cancel(req.transferId(),site.id(),site.token())).hasMessageContaining("401");
    }
    @Test void supersededSealAndActivationCannotRestoreOldAuthority()throws Exception {
        var site=site();var req=prepare(site);service.prepare(req,site.token());seal(site,req.transferId());rotate(site);
        assertThatThrownBy(()->service.activate(req.transferId(),activate(site,req.transferId()))).hasMessageContaining("TRANSFER_SOURCE_SUPERSEDED");
        var second=site();var r2=prepare(second);service.prepare(r2,second.token());seal(second,r2.transferId());service.activate(r2.transferId(),activate(second,r2.transferId()));rotate(second);
        assertThatThrownBy(()->service.activate(r2.transferId(),activate(second,r2.transferId()))).hasMessageContaining("TRANSFER_ACTIVATION_SUPERSEDED");
    }
    @Test void concurrentCancellationAndActivationHaveOnlyOneWinner()throws Exception {
        var site=site();var req=prepare(site);service.prepare(req,site.token());seal(site,req.transferId());
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();try{service.activate(req.transferId(),activate(site,req.transferId()));return "ACTIVATED";}catch(Exception e){return "REJECTED";}});
            var b=pool.submit(()->{start.await();try{service.cancel(req.transferId(),site.id(),site.token());return "CANCELLED";}catch(Exception e){return "REJECTED";}});
            start.countDown();var results=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
            assertThat(results).contains("REJECTED");assertThat(results.stream().filter(v->!v.equals("REJECTED")).count()).isEqualTo(1);
        }
    }
    @Test void httpPrepareAuthenticatesSourceAndDestinationProof()throws Exception {
        var site=site();var req=prepare(site);String body=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(req);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/server-transfers/prepare")
                .contentType("application/json").header("X-TPV-Installation-Token","wrong-token").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/server-transfers/prepare")
                .contentType("application/json").header("X-TPV-Installation-Token",site.token()).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.state").value("PREPARED"));
    }
    @Test void cancellingUnrecordedPreparationCreatesDurableTombstone()throws Exception {
        var site=site();var req=prepare(site);
        assertThat(service.cancel(req.transferId(),site.id(),site.token(),req.destinationPublicKey()).state()).isEqualTo("CANCELLED");
        assertThat(service.status(req.transferId(),site.id(),site.token()).state()).isEqualTo("CANCELLED");
        assertThat(service.cancel(req.transferId(),site.id(),site.token(),req.destinationPublicKey()).state()).isEqualTo("CANCELLED");
        assertThat(service.prepare(req,site.token()).state()).isEqualTo("CANCELLED");
        assertThatThrownBy(()->seal(site,req.transferId())).hasMessageContaining("TRANSFER_STATE_CONFLICT");
        var other=site();
        assertThatThrownBy(()->service.cancel(req.transferId(),other.id(),other.token(),req.destinationPublicKey())).hasMessageContaining("TRANSFER_NOT_FOUND");
        rotate(site);
        assertThatThrownBy(()->service.cancel(UUID.randomUUID(),site.id(),site.token(),req.destinationPublicKey())).hasMessageContaining("401");
    }
    private void rotate(Site site){new TransactionTemplate(transactions).executeWithoutResult(tx->{var value=installations.findByInstallationIdForUpdate(site.id()).orElseThrow();value.rotateTokenHash(tokens.hash(tokens.newToken()));installations.save(value);});}
    private ServerTransferController.Prepare prepare(Site site)throws Exception {
        String key=Base64.getEncoder().encodeToString(destination.getPublic().getEncoded());
        String issued=Instant.now().toString();long bytes=8L*1024*1024*1024;
        var proof=Map.of("releaseId","test-release","schemaVersion","V275","freeBytes",bytes,"issuedAt",issued,"publicHost","test.local","tlsReady",false,
                "signature",sign("TPV-DESTINATION-V2\n"+TransferCryptography.fingerprint(key)+"\ntest-release\nV275\n"+bytes+"\n"+issued+"\ntest.local\nfalse"));
        return new ServerTransferController.Prepare(UUID.randomUUID(),site.id(),key,Base64.getEncoder().encodeToString(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(proof)));
    }
    private ServerTransferController.Seal sealRequest(Site site){return new ServerTransferController.Seal(site.id(),"a".repeat(64),"b".repeat(64));}
    private ServerTransferService.Status seal(Site site,UUID id){return service.seal(id,sealRequest(site),site.token());}
    private ServerTransferController.Activate activate(Site site,UUID id)throws Exception {
        String fingerprint=TransferCryptography.fingerprint(Base64.getEncoder().encodeToString(destination.getPublic().getEncoded()));
        return new ServerTransferController.Activate(site.id(),"a".repeat(64),"b".repeat(64),sign("TPV-ACTIVATE-V1\n"+id+"\n"+site.id()+"\n"+"a".repeat(64)+"\n"+"b".repeat(64)+"\n"+fingerprint));
    }
    private static String sign(String payload)throws Exception {var signature=Signature.getInstance("SHA256withRSA");signature.initSign(destination.getPrivate());signature.update(payload.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(signature.sign());}
    private Site site(){
        var company=companies.saveAndFlush(new SaasCompany(UUID.randomUUID(),"Transfer test",validCif("B"+NUMBER.getAndIncrement()+"0"),TaxpayerType.SOCIEDAD,TaxRegime.IVA,Instant.now()));
        var store=stores.saveAndFlush(new SaasStore(UUID.randomUUID(),company,"001","Store","Atlantic/Canary",Instant.now()));
        var license=licenses.saveAndFlush(new SaasLicense(UUID.randomUUID(),company,"TRANSFER-"+UUID.randomUUID(),Instant.now().plusSeconds(86400),1,1,Instant.now()));
        String token=tokens.newToken();var installation=installations.saveAndFlush(new SaasInstallation(UUID.randomUUID(),company,store,license,UUID.randomUUID(),"TRANSFER-TEST",null,tokens.hash(token),Instant.now()));
        return new Site(installation.getInstallationId(),token);
    }
    record Site(UUID id,String token){}
}

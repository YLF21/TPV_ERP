package com.tpverp.saas.disasterrecovery;

import static com.tpverp.saas.disasterrecovery.DisasterRecoveryModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.JacksonConfiguration;
import com.tpverp.saas.admin.AdminAuthInterceptor;
import com.tpverp.saas.backup.CloudBackupDescriptor;
import com.tpverp.saas.tenant.TenantAuthInterceptor;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(DisasterRecoveryController.class)
@Import(JacksonConfiguration.class)
class DisasterRecoveryMvcBoundaryTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean DisasterRecoveryService service;
    @MockitoBean AdminAuthInterceptor adminAuthInterceptor;
    @MockitoBean TenantAuthInterceptor tenantAuthInterceptor;

    @Test void jackson3MvcAcceptsReportAndRendersAllEvidenceBoundaries() throws Exception {
        UUID id=UUID.randomUUID(),ticket=UUID.randomUUID(),installation=UUID.randomUUID(),backup=UUID.randomUUID();
        String digest="a".repeat(64), checkpoint="b".repeat(64);
        var evidence=mapper.createObjectNode().put("checkpoint",checkpoint).put("eventCount",2L)
                .put("projectionLedgerComplete",true).put("projectionErrors",0);
        evidence.putArray("events").add(mapper.createObjectNode().put("eventId",UUID.randomUUID().toString())
                .put("storeSequence",2_147_483_648L));
        var descriptor=new CloudBackupDescriptor(backup,installation,4,Instant.parse("2026-10-23T10:00:00Z"),
                "LAB",1,1,128,digest,digest,2);
        var state=new State(id,ticket,installation,backup,"SUPPORT_REVIEW",3,"c".repeat(64),digest,
                Instant.parse("2026-10-24T10:00:00Z"),List.of(),0,descriptor,evidence,List.of());
        when(service.received(eq(id),any(Received.class))).thenAnswer(invocation->{
            Received received=invocation.getArgument(1);
            assertThat(received.destinationProof()).isEqualTo("signed-proof");
            assertThat(received.report().toString()).isEqualTo("{\"backupSha256\":\""+digest+"\",\"snapshotVerified\":true,\"unresolvedPayments\":0}");
            return state;
        });
        when(service.inspect(eq(id),any(Proof.class))).thenReturn(state);
        when(service.evidence(eq(id),isNull(),any(Proof.class))).thenReturn(evidence);
        when(service.activate(eq(id),any(Proof.class))).thenReturn(new Activation(state,"encrypted-lab-credential"));

        String receipt="{\"destinationProof\":\"signed-proof\",\"report\":{\"backupSha256\":\""+digest
                +"\",\"snapshotVerified\":true,\"unresolvedPayments\":0}}";
        mvc.perform(post("/api/v1/backup-recoveries/"+id+"/received").contentType(MediaType.APPLICATION_JSON).content(receipt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.checkpoint").value(checkpoint))
                .andExpect(jsonPath("$.evidence.eventCount").value(2))
                .andExpect(jsonPath("$.evidence.events[0].storeSequence").value(2_147_483_648L));
        mvc.perform(post("/api/v1/backup-recoveries/"+id+"/inspect").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationProof\":\"signed-proof\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.evidence.checkpoint").value(checkpoint));
        mvc.perform(post("/api/v1/backup-recoveries/"+id+"/evidence").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationProof\":\"signed-proof\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.checkpoint").value(checkpoint))
                .andExpect(jsonPath("$.events[0].storeSequence").value(2_147_483_648L));
        mvc.perform(post("/api/v1/backup-recoveries/"+id+"/activate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationProof\":\"signed-proof\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status.evidence.checkpoint").value(checkpoint));
        mvc.perform(post("/api/v1/backup-recoveries/"+id+"/received").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationProof\":\"signed-proof\",\"report\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/backup-recoveries/"+id+"/received").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationProof\":\"signed-proof\",\"report\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test void jackson3ReceiptPreservesJackson2SignedFieldOrderAndLongValues() throws Exception {
        UUID id=UUID.randomUUID(),ticket=UUID.randomUUID(),installation=UUID.randomUUID(),backup=UUID.randomUUID();
        String digest="d".repeat(64);
        String report="{\"unresolvedPayments\":0,\"backupSha256\":\""+digest
                +"\",\"centralEventCount\":2147483648,\"snapshotVerified\":true}";
        String canonical=mapper.readTree(report).toString();
        assertThat(canonical).isEqualTo(report);
        String reportHash=RecoveryCryptography.hash(canonical.getBytes(StandardCharsets.UTF_8));
        var keys=KeyPairGenerator.getInstance("RSA");keys.initialize(3072);var pair=keys.generateKeyPair();
        String publicKey=Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        String signedText="TPV-RECOVERY-V1\n"+id+"\nreceived\n"+digest+"\n"+reportHash;
        var signature=Signature.getInstance("SHA256withRSA");signature.initSign(pair.getPrivate());
        signature.update(signedText.getBytes(StandardCharsets.UTF_8));
        String proof=Base64.getEncoder().encodeToString(signature.sign());
        var accepted=new AtomicInteger();
        var state=new State(id,ticket,installation,backup,"SUPPORT_REVIEW",1,"f".repeat(64),digest,
                Instant.parse("2026-10-24T10:00:00Z"),List.of(),0,null,mapper.createObjectNode(),List.of());
        when(service.received(eq(id),any(Received.class))).thenAnswer(invocation->{
            Received input=invocation.getArgument(1);
            String actualHash=RecoveryCryptography.hash(input.report().toString().getBytes(StandardCharsets.UTF_8));
            String actualText="TPV-RECOVERY-V1\n"+id+"\nreceived\n"+digest+"\n"+actualHash;
            if(!RecoveryCryptography.verify(publicKey,actualText,input.destinationProof()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"RECOVERY_DESTINATION_PROOF_INVALID");
            assertThat(input.report().path("centralEventCount").asLong()).isEqualTo(2_147_483_648L);
            accepted.incrementAndGet();
            return state;
        });

        String prefix="{\"destinationProof\":\""+proof+"\",\"report\":";
        mvc.perform(post("/api/v1/backup-recoveries/"+id+"/received").contentType(MediaType.APPLICATION_JSON)
                        .content(prefix+report+"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/backup-recoveries/"+id+"/received").contentType(MediaType.APPLICATION_JSON)
                        .content(prefix+report.replace("\"snapshotVerified\":true","\"snapshotVerified\":false")+"}"))
                .andExpect(status().isForbidden());
        String reordered="{\"backupSha256\":\""+digest
                +"\",\"unresolvedPayments\":0,\"centralEventCount\":2147483648,\"snapshotVerified\":true}";
        mvc.perform(post("/api/v1/backup-recoveries/"+id+"/received").contentType(MediaType.APPLICATION_JSON)
                        .content(prefix+reordered+"}"))
                .andExpect(status().isForbidden());
        assertThat(accepted).hasValue(1);
    }
}

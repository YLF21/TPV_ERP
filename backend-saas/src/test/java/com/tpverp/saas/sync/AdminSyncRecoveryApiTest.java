package com.tpverp.saas.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.ProvisionedCompany;
import com.tpverp.saas.ProvisioningRequest;
import com.tpverp.saas.SaasTestData;
import com.tpverp.saas.license.CommercialProfile;
import com.tpverp.saas.license.LicenseSaasLinkRequest;
import com.tpverp.saas.license.LicenseSaasLinkResponse;
import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.TaxpayerType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "tpv.saas.sync-recovery.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminSyncRecoveryApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    private ProvisionedCompany company;
    private UUID eventId;

    @BeforeEach
    void fixture() throws Exception {
        String taxId = SaasTestData.validCif("B" + String.format("%07d", java.util.concurrent.ThreadLocalRandom.current().nextInt(10_000_000)) + "0");
        company = SaasTestData.provisionCompany(mvc, mapper, new ProvisioningRequest(
                "Recovery fixture", taxId, TaxpayerType.SOCIEDAD, TaxRegime.IGIC,
                CommercialProfile.MAYORISTA, SaasTestData.fiscalAddress(), "001", "Store",
                SaasTestData.fiscalAddress(), "Atlantic/Canary", Instant.parse("2099-07-01T00:00:00Z"), 2, 1));
        var linkResult = mvc.perform(post("/api/v1/license/link")
                .header("X-TPV-Link-Recovery-Token", "recovery-token-0123456789abcdef0123456789abcdef")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new LicenseSaasLinkRequest(company.pairingCode(),
                        UUID.randomUUID(), "INST-RECOVERY", "public-key", company.storeId(), "001",
                        "DEMO-00000000", "Empresa", null, null, "Atlantic/Canary"))))
                .andExpect(status().isOk()).andReturn();
        var link = mapper.readValue(linkResult.getResponse().getContentAsString(), LicenseSaasLinkResponse.class);
        eventId = UUID.randomUUID();
        mvc.perform(post("/api/v1/sync/events")
                .header("X-TPV-Installation-Token", link.installationToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new SyncEventRequest(eventId, company.companyId(),
                        company.storeId(), 123L, null, "STOCK_MOVEMENT", UUID.randomUUID(),
                        SyncOperation.CREAR, Map.of("cantidad", 7, "productoId", UUID.randomUUID(), "almacenId", UUID.randomUUID())))))
                .andExpect(status().isOk());
        jdbc.update("update saas_sync_event set projection_status='RECEIVED', projected_at=null where event_id=?", eventId);
    }

    @AfterEach
    void removeEventFixture() {
        // Negative tests deliberately corrupt events; do not expose them to other API suites.
        if (eventId != null) {
            jdbc.update("delete from saas_sync_event where event_id=?", eventId);
        }
    }

    @Test
    void preservesReceiptAndStockAndAuditsOnceAcrossRetries() throws Exception {
        String before = immutableEvent();
        String body = body(company.companyId(), hash(), "RECEIVED");
        mvc.perform(post(endpoint()).header("Authorization", basic("admin", "admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IGNORED"))
                .andExpect(jsonPath("$.replayed").value(false));
        Object projectedAt = jdbc.queryForObject("select projected_at from saas_sync_event where event_id=?", Object.class, eventId);
        mvc.perform(post(endpoint()).header("Authorization", basic("admin", "admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        assertThat(immutableEvent()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select projected_at from saas_sync_event where event_id=?", Object.class, eventId)).isEqualTo(projectedAt);
        assertThat(auditCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select sum((payload::jsonb->>'cantidad')::numeric) from saas_sync_event where company_id=? and entity_type='STOCK_MOVEMENT'", Integer.class, company.companyId())).isEqualTo(7);
    }

    @Test
    void rejectsAnonymousAndReadOnlyUserWithoutChangingEvent() throws Exception {
        String before = fullEvent();
        String body = body(company.companyId(), hash(), "RECEIVED");
        mvc.perform(post(endpoint()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(endpoint()).header("Authorization", basic("viewer", "admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        assertThat(fullEvent()).isEqualTo(before);
        assertThat(auditCount()).isZero();
    }

    @Test
    void rejectsWrongCompanyHashAndStaleState() throws Exception {
        String before = fullEvent();
        mvc.perform(post(endpoint()).header("Authorization", basic("admin", "admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), hash(), "RECEIVED")))
                .andExpect(status().isNotFound());
        mvc.perform(post(endpoint()).header("Authorization", basic("admin", "admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body(company.companyId(), "0".repeat(64), "RECEIVED")))
                .andExpect(status().isConflict());
        mvc.perform(post(endpoint()).header("Authorization", basic("admin", "admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body(company.companyId(), hash(), "ERROR")))
                .andExpect(status().isConflict());
        assertThat(fullEvent()).isEqualTo(before);
        assertThat(auditCount()).isZero();
    }

    @Test
    void rejectsTamperedPayloadEvenWhenStoredHashMatchesRequest() throws Exception {
        String hash = hash();
        jdbc.update("update saas_sync_event set payload='{}' where event_id=?", eventId);
        String before = fullEvent();
        mvc.perform(post(endpoint()).header("Authorization", basic("admin", "admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body(company.companyId(), hash, "RECEIVED")))
                .andExpect(status().isConflict());
        assertThat(fullEvent()).isEqualTo(before);
        assertThat(auditCount()).isZero();
    }

    @Test
    void rollsBackProjectionFailureIncludingOriginalErrorAndFailureTracking() throws Exception {
        String payload = "{}";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("update saas_sync_event set entity_type='FISCAL_STATUS', operation='ACTUALIZAR', payload=?,payload_hash=?,projection_status='ERROR',projection_error='original failure' where event_id=?", payload, hash, eventId);
        String before = fullEvent();
        Long failures = jdbc.queryForObject("select count(*) from saas_store_failure", Long.class);
        mvc.perform(post(endpoint()).header("Authorization", basic("admin", "admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body(company.companyId(), hash, "ERROR")))
                .andExpect(status().is4xxClientError());
        assertThat(fullEvent()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from saas_store_failure", Long.class)).isEqualTo(failures);
        assertThat(auditCount()).isZero();
    }

    @Test
    void rejectsIncompatibleOriginAndInvalidRecoveryRequest() throws Exception {
        jdbc.update("update saas_sync_event set store_id=null where event_id=?", eventId);
        String before = fullEvent();
        mvc.perform(post(endpoint()).header("Authorization", basic("admin", "admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body(company.companyId(), hash(), "RECEIVED")))
                .andExpect(status().isConflict());
        mvc.perform(post(endpoint()).header("Authorization", basic("admin", "admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body(company.companyId(), hash(), "PROJECTED")))
                .andExpect(status().isBadRequest());
        assertThat(fullEvent()).isEqualTo(before);
        assertThat(auditCount()).isZero();
    }

    private String endpoint() { return "/api/v1/admin/sync/events/" + eventId + "/recover"; }
    private String body(UUID companyId, String hash, String status) throws Exception {
        return mapper.writeValueAsString(Map.of("companyId", companyId, "expectedPayloadHash", hash,
                "expectedStatus", status, "reason", "Recover historical events without changing source data"));
    }
    private String hash() { return jdbc.queryForObject("select payload_hash from saas_sync_event where event_id=?", String.class, eventId); }
    private String fullEvent() { return jdbc.queryForObject("select to_jsonb(e)::text from saas_sync_event e where event_id=?", String.class, eventId); }
    private String immutableEvent() { return jdbc.queryForObject("select (to_jsonb(e)-'projection_status'-'projected_at'-'projection_error')::text from saas_sync_event e where event_id=?", String.class, eventId); }
    private long auditCount() { return jdbc.queryForObject("select count(*) from saas_admin_audit_log where action='RECOVER_SYNC_EVENT' and target_id=?", Long.class, eventId.toString()); }
    private String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}

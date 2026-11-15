package com.tpverp.saas.sync;

import static com.tpverp.saas.SaasTestData.validCif;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tpverp.saas.license.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {
        "spring.flyway.default-schema=replenishment_test",
        "spring.datasource.hikari.schema=replenishment_test",
        "spring.jpa.properties.hibernate.default_schema=replenishment_test"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReplenishmentRequestPostgreSqlTest {
    private static final AtomicInteger COMPANY = new AtomicInteger(9300000);
    @Autowired SyncEventService sync;
    @Autowired AdminSyncRecoveryService recovery;
    @Autowired ReplenishmentExpiryService expiry;
    @Autowired SaasSyncEventRepository events;
    @Autowired SaasCompanyRepository companies;
    @Autowired SaasStoreRepository stores;
    @Autowired SaasLicenseRepository licenses;
    @Autowired SaasInstallationRepository installations;
    @Autowired TokenHasher tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;

    @BeforeEach
    void isolatedSchema() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo("replenishment_test");
    }

    @Test
    void projectsCurrentSnapshotAndPurgesEventPayloadWithDuplicateAck() {
        Site site = site();
        UUID id = UUID.randomUUID();
        var request = request(site, id, 0, SyncOperation.CREAR, payload(id, 0));
        assertThat(sync.receive(request, site.token()).projectionStatus())
                .isEqualTo(SaasSyncEvent.ProjectionStatus.PROJECTED);
        assertThat(sync.receive(request, site.token()).projectionStatus())
                .isEqualTo(SaasSyncEvent.ProjectionStatus.PROJECTED);
        assertThat(row(site, id)).containsEntry("state", "PENDING")
                .containsEntry("revision", 0L);
        assertThat((BigDecimal) row(site, id).get("quantity")).isEqualByComparingTo("2.125");
        assertThat(events.findById(request.eventId()).orElseThrow().getPayload())
                .isEqualTo(ReplenishmentRequestProjector.PURGED_PAYLOAD);
        String hash = events.findById(request.eventId()).orElseThrow().getPayloadHash();
        assertThat(recovery.recover(request.eventId(), new AdminSyncRecoveryService.Request(
                site.company().getId(), hash, SaasSyncEvent.ProjectionStatus.RECEIVED, "verify purged"))
                .replayed()).isTrue();
    }

    @Test
    void httpReceiptIncludesProjectedStatusForNewAndDuplicateEvents() throws Exception {
        Site site = site();
        UUID id = UUID.randomUUID();
        var request = request(site, id, 0, SyncOperation.CREAR, payload(id, 0));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/v1/sync/events")
                            .header("X-TPV-Installation-Token", site.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.eventId").value(request.eventId().toString()))
                    .andExpect(jsonPath("$.accepted").value(true))
                    .andExpect(jsonPath("$.projectionStatus").value("PROJECTED"));
        }
    }

    @Test
    void isolatesTrustedStoreAndCompanyAndRejectsSpoofedOrigin() {
        Site a = site();
        Site otherStore = site(a.company(), "002");
        Site otherCompany = site();
        UUID id = UUID.randomUUID();
        sync.receive(request(a, id, 0, SyncOperation.CREAR, payload(id, 0)), a.token());
        sync.receive(request(otherStore, id, 0, SyncOperation.CREAR, payload(id, 0)), otherStore.token());
        sync.receive(request(otherCompany, id, 0, SyncOperation.CREAR, payload(id, 0)), otherCompany.token());
        assertThat(jdbc.queryForObject("select count(*) from saas_replenishment_request where request_id=?",
                Integer.class, id)).isEqualTo(3);
        var forged = request(otherStore, UUID.randomUUID(), 0, SyncOperation.CREAR,
                payload(UUID.randomUUID(), 0));
        assertThatThrownBy(() -> sync.receive(forged, a.token()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
    }

    @Test
    void ignoresOlderRevisionAndRejectsSameRevisionWithDifferentPayload() {
        Site site = site();
        UUID id = UUID.randomUUID();
        var v1 = payload(id, 1);
        sync.receive(request(site, id, 1, SyncOperation.ACTUALIZAR, v1), site.token());
        var old = payload(id, 0);
        sync.receive(request(site, id, 0, SyncOperation.CREAR, old), site.token());
        assertThat(row(site, id)).containsEntry("revision", 1L);
        var conflict = payload(id, 1);
        conflict.put("quantity", new BigDecimal("7.000"));
        var conflictingRequest = request(site, id, 1, SyncOperation.ACTUALIZAR, conflict);
        assertThatThrownBy(() -> sync.receive(conflictingRequest, site.token()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThat(events.findById(conflictingRequest.eventId())).isEmpty();
        assertThat((BigDecimal) row(site, id).get("quantity")).isEqualByComparingTo("2.125");
    }

    @Test
    void deletesOnlyCurrentRequestAndKeepsRevisionFence() {
        Site site = site();
        UUID id = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        sync.receive(request(site, id, 0, SyncOperation.CREAR, payload(id, 0)), site.token());
        sync.receive(request(site, second, 0, SyncOperation.CREAR, payload(second, 0)), site.token());
        sync.receive(request(site, id, 1, SyncOperation.BORRAR,
                Map.of("schemaVersion", 1, "requestId", id.toString(), "revision", 1)), site.token());
        assertThat(row(site, id)).containsEntry("state", "DELETED").containsEntry("revision", 1L)
                .containsEntry("product_id", null).containsEntry("metadata_json", null);
        assertThat(row(site, second)).containsEntry("state", "PENDING");
        sync.receive(request(site, id, 0, SyncOperation.CREAR, payload(id, 0)), site.token());
        assertThat(row(site, id)).containsEntry("state", "DELETED");
    }

    @Test
    void expiresOrderedAndAllowsNewerPendingRevision() {
        Site site = site();
        UUID id = UUID.randomUUID();
        var ordered = payload(id, 0);
        ordered.put("status", "ORDERED");
        ordered.put("orderDate", "2026-01-01");
        ordered.put("expiresAt", "2100-02-01T00:00:00Z");
        sync.receive(request(site, id, 0, SyncOperation.CREAR, ordered), site.token());
        jdbc.update("update saas_replenishment_request set expires_at=? where request_id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), id);
        assertThat(expiry.purgeExpired()).isGreaterThanOrEqualTo(1);
        assertThat(row(site, id)).containsEntry("state", "EXPIRED")
                .containsEntry("product_id", null).containsEntry("metadata_json", null);
        sync.receive(request(site, id, 1, SyncOperation.ACTUALIZAR, payload(id, 1)), site.token());
        assertThat(row(site, id)).containsEntry("state", "PENDING").containsEntry("revision", 1L);
    }

    @Test
    void invalidDecimalAndMetadataRollBackEventAndProjection() {
        Site site = site();
        UUID id = UUID.randomUUID();
        var precise = payload(id, 0);
        precise.put("quantity", new BigDecimal("1.0001"));
        var invalid = request(site, id, 0, SyncOperation.CREAR, precise);
        assertThatThrownBy(() -> sync.receive(invalid, site.token()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        assertThat(events.findById(invalid.eventId())).isEmpty();
        assertThat(rowCount(site, id)).isZero();
        var huge = payload(id, 0);
        huge.put("metadata", Map.of("productName", "x".repeat(17_000)));
        assertThatThrownBy(() -> sync.receive(request(site, id, 0, SyncOperation.CREAR, huge), site.token()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        assertThat(rowCount(site, id)).isZero();
    }

    @Test
    void preservesSixteenIntegerAndThreeFractionalDigitsFromDecimalString() {
        Site site = site();
        UUID id = UUID.randomUUID();
        var exact = payload(id, 0);
        exact.put("quantity", "9999999999999999.999");
        sync.receive(request(site, id, 0, SyncOperation.CREAR, exact), site.token());
        assertThat((BigDecimal) row(site, id).get("quantity"))
                .isEqualByComparingTo("9999999999999999.999");
    }

    @Test
    void alreadyExpiredOrderedEventKeepsOnlyMinimalFence() {
        Site site = site();
        UUID id = UUID.randomUUID();
        var ordered = payload(id, 0);
        ordered.put("status", "ORDERED");
        ordered.put("orderDate", "2026-01-01");
        ordered.put("expiresAt", "2026-02-01T00:00:00Z");
        sync.receive(request(site, id, 0, SyncOperation.CREAR, ordered), site.token());
        assertThat(row(site, id)).containsEntry("state", "EXPIRED")
                .containsEntry("product_id", null).containsEntry("metadata_json", null);
    }

    @Test
    void upgradesPreviouslyIgnoredEventAndReturnsProjectedAck() throws Exception {
        Site site = site();
        UUID id = UUID.randomUUID();
        var request = request(site, id, 0, SyncOperation.CREAR, payload(id, 0));
        persistOldIgnored(site, request);

        assertThat(sync.receive(request, site.token()).projectionStatus())
                .isEqualTo(SaasSyncEvent.ProjectionStatus.PROJECTED);
        assertThat(row(site, id)).containsEntry("state", "PENDING");
        assertThat(events.findById(request.eventId()).orElseThrow().getPayload())
                .isEqualTo(ReplenishmentRequestProjector.PURGED_PAYLOAD);
    }

    @Test
    void newerDeletePurgesEarlierIgnoredPayloadAndAcknowledgesItsRetry() throws Exception {
        Site site = site();
        UUID id = UUID.randomUUID();
        var oldRequest = request(site, id, 0, SyncOperation.CREAR, payload(id, 0));
        persistOldIgnored(site, oldRequest);
        var deletion = request(site, id, 1, SyncOperation.BORRAR,
                Map.of("schemaVersion", 1, "requestId", id.toString(), "revision", 1));
        assertThat(sync.receive(deletion, site.token()).projectionStatus())
                .isEqualTo(SaasSyncEvent.ProjectionStatus.PROJECTED);
        assertThat(events.findById(oldRequest.eventId()).orElseThrow().getPayload())
                .isEqualTo(ReplenishmentRequestProjector.PURGED_PAYLOAD);
        assertThat(sync.receive(oldRequest, site.token()).projectionStatus())
                .isEqualTo(SaasSyncEvent.ProjectionStatus.PROJECTED);
        assertThat(row(site, id)).containsEntry("state", "DELETED").containsEntry("revision", 1L);
    }

    @Test
    void newerDeleteDoesNotPurgeIgnoredEventFromAnotherInstallation() throws Exception {
        Site otherInstallation = site();
        UUID id = UUID.randomUUID();
        var otherRequest = request(otherInstallation, id, 0, SyncOperation.CREAR, payload(id, 0));
        persistOldIgnored(otherInstallation, otherRequest);
        String original = events.findById(otherRequest.eventId()).orElseThrow().getPayload();
        otherInstallation.installation().revoke(Instant.now(), "test", "replacement");
        installations.saveAndFlush(otherInstallation.installation());
        Site site = installation(otherInstallation.company(), otherInstallation.store());

        sync.receive(request(site, id, 1, SyncOperation.BORRAR,
                Map.of("schemaVersion", 1, "requestId", id.toString(), "revision", 1)), site.token());

        var untouched = events.findById(otherRequest.eventId()).orElseThrow();
        assertThat(untouched.getPayload()).isEqualTo(original);
        assertThat(untouched.getProjectionStatus()).isEqualTo(SaasSyncEvent.ProjectionStatus.IGNORED);
    }

    private void persistOldIgnored(Site site, SyncEventRequest request) throws Exception {
        String canonical = mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(request.payload());
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        var ignored = new SaasSyncEvent(request.eventId(), site.company(), site.store(),
                site.installation(), request.storeSequence(), request.entityType(), request.entityId(),
                request.operation(), canonical, hash, 1, Instant.now());
        ignored.markIgnored(Instant.now());
        events.saveAndFlush(ignored);
    }

    private Map<String, Object> payload(UUID id, long revision) {
        Map<String, Object> result = new HashMap<>();
        result.put("schemaVersion", 1);
        result.put("requestId", id.toString());
        result.put("revision", revision);
        result.put("productId", UUID.randomUUID().toString());
        result.put("warehouseId", UUID.randomUUID().toString());
        result.put("quantity", new BigDecimal("2.125"));
        result.put("reason", "Reponer lineal");
        result.put("status", "PENDING");
        result.put("orderDate", null);
        result.put("createdAt", "2026-01-01T00:00:00Z");
        result.put("expiresAt", null);
        result.put("metadata", Map.of("productCode", "0007", "supplierName", "Proveedor"));
        return result;
    }

    private SyncEventRequest request(Site site, UUID id, long revision, SyncOperation operation,
            Map<String, Object> payload) {
        return new SyncEventRequest(UUID.randomUUID(), site.company().getId(), site.store().getId(),
                revision + 1, null, ReplenishmentRequestProjector.ENTITY_TYPE, id, operation, payload);
    }

    private Map<String, Object> row(Site site, UUID id) {
        return jdbc.queryForMap("select * from saas_replenishment_request where company_id=? and store_id=? and request_id=?",
                site.company().getId(), site.store().getId(), id);
    }

    private int rowCount(Site site, UUID id) {
        return jdbc.queryForObject("select count(*) from saas_replenishment_request where company_id=? and store_id=? and request_id=?",
                Integer.class, site.company().getId(), site.store().getId(), id);
    }

    private Site site() {
        SaasCompany company = companies.saveAndFlush(new SaasCompany(UUID.randomUUID(), "Replenishment test company",
                validCif("B" + COMPANY.getAndIncrement() + "0"), TaxpayerType.SOCIEDAD,
                TaxRegime.IVA, Instant.now()));
        return site(company, "001");
    }

    private Site site(SaasCompany company, String code) {
        SaasStore store = stores.saveAndFlush(new SaasStore(UUID.randomUUID(), company, code, "Test store",
                "Atlantic/Canary", Instant.now()));
        return installation(company, store);
    }

    private Site installation(SaasCompany company, SaasStore store) {
        SaasLicense license = licenses.saveAndFlush(new SaasLicense(UUID.randomUUID(), company,
                "REPLENISHMENT-TEST-" + UUID.randomUUID(), Instant.now().plusSeconds(86400), 1, 1, Instant.now()));
        String token = tokens.newToken();
        SaasInstallation installation = installations.saveAndFlush(new SaasInstallation(UUID.randomUUID(), company,
                store, license, UUID.randomUUID(), "REPLENISHMENT-TEST", null, tokens.hash(token), Instant.now()));
        return new Site(company, store, installation, token);
    }

    private record Site(SaasCompany company, SaasStore store, SaasInstallation installation, String token) { }
}

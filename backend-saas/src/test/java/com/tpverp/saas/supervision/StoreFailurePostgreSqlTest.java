package com.tpverp.saas.supervision;

import static com.tpverp.saas.SaasTestData.validCif;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.*;
import com.tpverp.saas.sync.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StoreFailurePostgreSqlTest {
    private static final String SCHEMA = "store_failure_" + UUID.randomUUID().toString().replace("-", "");
    private static final AtomicInteger COMPANY = new AtomicInteger(9410000);
    @DynamicPropertySource
    static void schema(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }
    @Autowired SyncEventService sync;
    @Autowired StoreFailureQueryService queries;
    @Autowired SaasCompanyRepository companies;
    @Autowired SaasStoreRepository stores;
    @Autowired SaasLicenseRepository licenses;
    @Autowired SaasInstallationRepository installations;
    @Autowired TokenHasher tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void isolatedDatabase() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);
    }

    @Test
    void duplicateAndDelayedReportsDoNotIncreaseOccurrencesOrReopenResolvedFailure() {
        Site site = site(); UUID source = UUID.randomUUID();
        Map<String, Object> initial = payload(site, source, 1, "OPEN", 3);
        SyncEventRequest first = request(site, source, initial);
        sync.receive(first, site.token());
        sync.receive(first, site.token());
        sync.receive(request(site, source, initial), site.token());
        assertThat(rows(site)).singleElement().satisfies(row -> {
            assertThat(row.occurrences()).isEqualTo(3);
            assertThat(row.status()).isEqualTo("OPEN");
            assertThat(row.installationId()).isEqualTo(site.installation().getInstallationId());
        });
        sync.receive(request(site, source, payload(site, source, 2, "RESOLVED", 3)), site.token());
        sync.receive(request(site, source, initial), site.token());
        assertThat(rows(site)).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("RESOLVED");
            assertThat(row.occurrences()).isEqualTo(3);
        });
        assertThatThrownBy(() -> sync.receive(request(site, source, payload(site, source, 2, "OPEN", 9)), site.token()))
                .hasMessageContaining("Misma revision");
        assertThat(rows(site)).singleElement().extracting(StoreFailureView::status).isEqualTo("RESOLVED");
    }

    @Test
    void tokenCannotAttributeReportToAnotherCompanyStoreOrInstallationAndUnsafeFieldsRollback() throws Exception {
        Site owner = site(); Site foreign = site(); UUID source = UUID.randomUUID();
        mvc.perform(post("/api/v1/sync/events").header("X-TPV-Installation-Token", owner.token())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request(foreign, source, payload(foreign, source, 1, "OPEN", 1)))))
                .andExpect(status().isUnauthorized());
        Map<String, Object> wrongInstallation = payload(owner, source, 1, "OPEN", 1);
        wrongInstallation.put("installationId", foreign.installation().getInstallationId().toString());
        assertThatThrownBy(() -> sync.receive(request(owner, source, wrongInstallation), owner.token())).hasMessageContaining("Procedencia");
        Map<String, Object> secret = payload(owner, source, 1, "OPEN", 1); secret.put("password", "must-not-persist");
        UUID rejectedEvent = request(owner, source, secret).eventId();
        SyncEventRequest unsafe = new SyncEventRequest(rejectedEvent, owner.company().getId(), owner.store().getId(), null,
                StoreFailureProjector.ENTITY_TYPE, source, SyncOperation.ACTUALIZAR, secret);
        assertThatThrownBy(() -> sync.receive(unsafe, owner.token())).hasMessageContaining("Informe operativo invalido");
        assertThat(jdbc.queryForObject("select count(*) from saas_sync_event where event_id = ?", Long.class, rejectedEvent)).isZero();
        assertThat(rows(owner)).isEmpty(); assertThat(rows(foreign)).isEmpty();
    }

    @Test
    void globalKeysetPaginationIncludesMoreThanTwoHundredAndFiltersActiveStoreAndSource() {
        Site active = site(); Site inactive = site();
        jdbc.update("update saas_store set active = false where id = ?", inactive.store().getId());
        Instant at = Instant.now().minusSeconds(60);
        for (int i = 0; i < 205; i++) insert(active, at, "OPEN");
        insert(inactive, at, "OPEN");
        StoreFailureQueryService.Filter filter = filter(active.company().getId(), true);
        var first = queries.page(filter, null, 200);
        assertThat(first.items()).hasSize(200); assertThat(first.hasMore()).isTrue();
        var next = queries.page(filter, first.nextCursor(), 200);
        assertThat(next.items()).hasSize(5); assertThat(next.hasMore()).isFalse();
        assertThat(first.items()).extracting(StoreFailureView::id).doesNotContainAnyElementsOf(next.items().stream().map(StoreFailureView::id).toList());
        assertThat(queries.page(filter(inactive.company().getId(), true), null, 50).items()).isEmpty();
        assertThat(queries.page(filter(inactive.company().getId(), false), null, 50).items()).singleElement()
                .satisfies(row -> assertThat(row.storeActive()).isFalse());
        StoreFailureView selected = first.items().getFirst();
        assertThat(queries.detail(selected.id())).isEqualTo(selected);
        var dateFiltered = new StoreFailureQueryService.Filter(active.company().getId(), active.store().getId(), active.installation().getInstallationId(),
                "LOCAL_SYNC", "RESOLVED", at.minusSeconds(1), at.plusSeconds(1), true, null);
        assertThat(queries.page(dateFiltered, null, 50).items()).isEmpty();
        assertThatThrownBy(() -> queries.page(filter, "invalid", 50)).hasMessageContaining("Cursor");
    }

    @Test
    void acknowledgedCentralFailureRemainsVisibleWithoutFabricatedStoreAndDoesNotBecomeResolved() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into saas_security_notification_outbox
                (id,event_type,realm,username_key,encrypted_payload,status,created_at,attempt_count,idempotency_key)
                values (?, 'SECURITY_ALERT','admin','test-user','encrypted-test','ACKNOWLEDGED',?,3,?)
                """, id, Timestamp.from(Instant.now().minusSeconds(30)), id.toString());
        var row = queries.detail("CENTRAL_SECURITY:" + id);
        assertThat(row.status()).isEqualTo("ACKNOWLEDGED"); assertThat(row.central()).isTrue();
        assertThat(row.storeId()).isNull(); assertThat(row.companyId()).isNull();
        assertThat(row.detail()).doesNotContain("test-user", "encrypted-test");
        mvc.perform(get("/api/v1/admin/supervision/failures")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/supervision/failures").header("Authorization", basic("admin", "admin"))
                        .queryParam("source", "CENTRAL_SECURITY"))
                .andExpect(status().isOk());
    }

    @Test
    void nativeProjectionFailuresPersistAndRepeatedDeliveryCountsRealAttempts() {
        Site site = site();
        SyncEventRequest badFiscal = new SyncEventRequest(UUID.randomUUID(), site.company().getId(), site.store().getId(), null,
                "FISCAL_STATUS", UUID.randomUUID(), SyncOperation.ACTUALIZAR, Map.of("installationId", site.installation().getInstallationId().toString()));
        assertThatThrownBy(() -> sync.receive(badFiscal, site.token())).hasMessageContaining("fiscal");
        assertThatThrownBy(() -> sync.receive(badFiscal, site.token())).hasMessageContaining("fiscal");
        assertThat(rows(site)).singleElement().satisfies(row -> {
            assertThat(row.source()).isEqualTo("SYNC_PROJECTION"); assertThat(row.occurrences()).isEqualTo(2);
            assertThat(row.status()).isEqualTo("OPEN");
        });
    }

    @Test
    void applicationDiagnosticsRemainIdempotentAndSearchableWithoutChangingLegacyPayloads() {
        Site site = site(); UUID source = UUID.randomUUID(); UUID trace = UUID.randomUUID();
        Map<String, Object> initial = applicationPayload(site, source, trace, 1, 2);
        SyncEventRequest first = request(site, source, initial);
        sync.receive(first, site.token());
        sync.receive(first, site.token());
        sync.receive(request(site, source, initial), site.token());
        StoreFailureView original = rows(site).getFirst();
        assertThat(original.occurrences()).isEqualTo(2);
        assertThat(original.receivedAt()).isNotNull();
        assertThat(original.module()).isEqualTo("SALES");
        assertThat(original.appVersion()).isEqualTo("0.0.1-SNAPSHOT");
        assertThat(original.traceId()).isEqualTo(trace.toString());
        assertThat(original.exceptionType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(original.errorLocation()).isEqualTo("com.tpverp.backend.sales.SaleService.save:123");
        assertThat(queries.detail(original.id())).isEqualTo(original);
        for (String search : java.util.List.of(trace.toString(), "SALES", "SUPERVISION-INSTALLATION", site.installation().getInstallationId().toString())) {
            assertThat(queries.page(new StoreFailureQueryService.Filter(site.company().getId(), site.store().getId(),
                    site.installation().getInstallationId(), "LOCAL_APPLICATION", "OPEN", null, null, true, search), null, 50).items())
                    .containsExactly(original);
        }
        UUID latestTrace = UUID.randomUUID();
        sync.receive(request(site, source, applicationPayload(site, source, latestTrace, 2, 4)), site.token());
        sync.receive(request(site, source, initial), site.token());
        assertThat(rows(site)).singleElement().satisfies(row -> {
            assertThat(row.traceId()).isEqualTo(latestTrace.toString());
            assertThat(row.occurrences()).isEqualTo(4);
            assertThat(row.status()).isEqualTo("OPEN");
        });
        assertThatThrownBy(() -> sync.receive(request(site, source, applicationPayload(site, source, trace, 2, 4)), site.token()))
                .hasMessageContaining("Misma revision");
        UUID legacySource = UUID.randomUUID();
        sync.receive(request(site, legacySource, payload(site, legacySource, 1, "OPEN", 1)), site.token());
        assertThat(rows(site).stream().filter(row -> row.source().equals("LOCAL_SYNC"))).singleElement().satisfies(row -> {
            assertThat(row.module()).isNull(); assertThat(row.traceId()).isNull(); assertThat(row.receivedAt()).isNotNull();
        });
        UUID nullableSource = UUID.randomUUID();
        Map<String, Object> nullable = applicationPayload(site, nullableSource, trace, 1, 1);
        for (String field : java.util.List.of("module", "appVersion", "traceId", "exceptionType", "errorLocation")) nullable.put(field, null);
        sync.receive(request(site, nullableSource, nullable), site.token());
        assertThat(rows(site)).hasSize(3);
    }

    @Test
    void diagnosticsRejectUntrustedMessagesUnknownFieldsInvalidVersionsAndForgedOwnership() {
        Site owner = site(); Site foreign = site(); UUID source = UUID.randomUUID();
        for (var badField : Map.<String, Object>ofEntries(
                Map.entry("schemaVersion", 3), Map.entry("module", "SALES password=secret"),
                Map.entry("appVersion", "v1 Authorization: Bearer secret"),
                Map.entry("traceId", "invalid trace ID"), Map.entry("exceptionType", "java.lang.Exception: email@example.com"),
                Map.entry("errorLocation", "C:/Users/customer/secret.txt:1"),
                Map.entry("message", "password=must-not-persist"), Map.entry("status", "RESOLVED"),
                Map.entry("severity", "INFO")).entrySet()) {
            Map<String, Object> bad = applicationPayload(owner, source, UUID.randomUUID(), 1, 1);
            bad.put(badField.getKey(), badField.getValue());
            SyncEventRequest rejected = request(owner, source, bad);
            assertThatThrownBy(() -> sync.receive(rejected, owner.token())).hasMessageContaining("Informe operativo invalido");
            assertThat(jdbc.queryForObject("select count(*) from saas_sync_event where event_id = ?", Long.class, rejected.eventId())).isZero();
        }
        for (String field : java.util.List.of("module", "appVersion", "traceId", "exceptionType", "errorLocation")) {
            Map<String, Object> missing = applicationPayload(owner, source, UUID.randomUUID(), 1, 1);
            missing.remove(field);
            assertThatThrownBy(() -> sync.receive(request(owner, source, missing), owner.token())).hasMessageContaining("Informe operativo invalido");
            Map<String, Object> oversized = applicationPayload(owner, source, UUID.randomUUID(), 1, 1);
            oversized.put(field, "A".repeat(241));
            assertThatThrownBy(() -> sync.receive(request(owner, source, oversized), owner.token())).hasMessageContaining("Informe operativo invalido");
        }
        SyncEventRequest unsupported = new SyncEventRequest(UUID.randomUUID(), owner.company().getId(), owner.store().getId(),
                null, StoreFailureProjector.ENTITY_TYPE, source, SyncOperation.CREAR, Map.of("message", "secret"));
        assertThatThrownBy(() -> sync.receive(unsupported, owner.token())).hasMessageContaining("Operacion de informe operativo no soportada");
        assertThat(jdbc.queryForObject("select count(*) from saas_sync_event where event_id = ?", Long.class, unsupported.eventId())).isZero();
        Map<String, Object> forged = applicationPayload(owner, source, UUID.randomUUID(), 1, 1);
        forged.put("installationId", foreign.installation().getInstallationId().toString());
        assertThatThrownBy(() -> sync.receive(request(owner, source, forged), owner.token())).hasMessageContaining("Procedencia");
        assertThatThrownBy(() -> sync.receive(request(foreign, source, applicationPayload(foreign, source, UUID.randomUUID(), 1, 1)), owner.token()));
        assertThat(rows(owner)).isEmpty(); assertThat(rows(foreign)).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "TPV_PHASE1_CAPTURED_PAYLOAD", matches = ".+")
    void actualLocalPublisherPayloadSurvivesAuthenticatedHttpAndAdminQuery() throws Exception {
        Site site = site();
        Map<String, Object> captured = mapper.readValue(java.nio.file.Files.readString(
                java.nio.file.Path.of(System.getenv("TPV_PHASE1_CAPTURED_PAYLOAD"))),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        captured.put("installationId", site.installation().getInstallationId().toString());
        UUID source = UUID.fromString((String) captured.get("sourceId"));
        SyncEventRequest request = request(site, source, captured);
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/v1/sync/events").header("X-TPV-Installation-Token", site.token())
                            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }
        var response = mvc.perform(get("/api/v1/admin/supervision/failures").header("Authorization", basic("admin", "admin"))
                        .queryParam("companyId", site.company().getId().toString()).queryParam("source", "LOCAL_APPLICATION")
                        .queryParam("q", (String) captured.get("traceId")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var items = mapper.readTree(response).path("items");
        assertThat(items.size()).isEqualTo(1);
        for (String field : java.util.List.of("module", "appVersion", "traceId", "exceptionType", "errorLocation")) {
            assertThat(items.get(0).path(field).asText()).isEqualTo(captured.get(field));
        }
        assertThat(items.get(0).path("receivedAt").asText()).isNotBlank();
        assertThat(items.get(0).path("occurrences").asLong()).isEqualTo(((Number) captured.get("occurrences")).longValue());
    }

    @Test
    void foreignDuplicateEventCannotMutateOriginalEventOrTraceReceipts() {
        Site owner = site(); Site foreign = site(); UUID source = UUID.randomUUID();
        SyncEventRequest original = request(owner, source, applicationPayload(owner, source, UUID.randomUUID(), 1, 1));
        sync.receive(original, owner.token());
        var eventBefore = jdbc.queryForMap("select * from saas_sync_event where event_id = ?", original.eventId());
        var failureBefore = rows(owner);
        var traceBefore = jdbc.queryForList("select * from saas_store_failure_trace where failure_id = ?",
                UUID.fromString(failureBefore.getFirst().id().split(":")[1]));
        SyncEventRequest foreignRequest = new SyncEventRequest(original.eventId(), foreign.company().getId(), foreign.store().getId(),
                null, StoreFailureProjector.ENTITY_TYPE, source, SyncOperation.ACTUALIZAR,
                applicationPayload(foreign, source, UUID.randomUUID(), 1, 1));
        assertThatThrownBy(() -> sync.receive(foreignRequest, foreign.token())).hasMessageContaining("Procedencia");
        assertThat(jdbc.queryForMap("select * from saas_sync_event where event_id = ?", original.eventId())).isEqualTo(eventBefore);
        assertThat(rows(owner)).isEqualTo(failureBefore);
        assertThat(jdbc.queryForList("select * from saas_store_failure_trace where failure_id = ?",
                UUID.fromString(failureBefore.getFirst().id().split(":")[1]))).isEqualTo(traceBefore);
        assertThat(rows(foreign)).isEmpty();
    }

    @Test
    void historicalCorrelationIdsRemainSearchableOutOfOrderWithoutAcceptingRevisionConflicts() {
        Site owner = site(); Site foreign = site(); UUID source = UUID.randomUUID();
        Map<String, Object> newer = applicationPayload(owner, source, UUID.randomUUID(), 2, 2);
        newer.put("traceId", "web-request-newer");
        Map<String, Object> older = applicationPayload(owner, source, UUID.randomUUID(), 1, 1);
        older.put("traceId", "web-request-older");
        sync.receive(request(owner, source, newer), owner.token());
        sync.receive(request(owner, source, older), owner.token());
        sync.receive(request(owner, source, older), owner.token());
        var latest = rows(owner).getFirst();
        assertThat(latest.traceId()).isEqualTo("web-request-newer");
        assertThat(latest.occurrences()).isEqualTo(2);
        for (String trace : java.util.List.of("web-request-newer", "web-request-older")) {
            assertThat(search(owner, trace)).containsExactly(latest);
            assertThat(search(foreign, trace)).isEmpty();
        }
        Map<String, Object> conflicting = new LinkedHashMap<>(older);
        conflicting.put("traceId", "web-injected-trace");
        assertThatThrownBy(() -> sync.receive(request(owner, source, conflicting), owner.token())).hasMessageContaining("Misma revision");
        assertThat(search(owner, "web-injected-trace")).isEmpty();
        assertThat(rows(owner)).containsExactly(latest);
        Map<String, Object> newest = applicationPayload(owner, source, UUID.randomUUID(), 3, 3);
        newest.put("traceId", "web-request-newest");
        sync.receive(request(owner, source, newest), owner.token());
        for (String trace : java.util.List.of("web-request-newer", "web-request-older", "web-request-newest")) {
            assertThat(search(owner, trace)).singleElement().satisfies(row -> {
                assertThat(row.occurrences()).isEqualTo(3);
                assertThat(row.traceId()).isEqualTo("web-request-newest");
            });
        }
        assertThat(jdbc.queryForObject("select count(*) from saas_store_failure_trace where failure_id = ?", Long.class,
                UUID.fromString(latest.id().split(":")[1]))).isEqualTo(3);
    }

    @Test
    void timestampsMustRemainInSupportedOperationalRange() {
        Site site = site(); UUID source = UUID.randomUUID();
        for (String badFirst : java.util.List.of("-10000-01-01T00:00:00Z", "1969-12-31T23:59:59Z")) {
            Map<String, Object> invalid = applicationPayload(site, source, UUID.randomUUID(), 1, 1);
            invalid.put("firstSeenAt", badFirst);
            SyncEventRequest request = request(site, source, invalid);
            assertThatThrownBy(() -> sync.receive(request, site.token()))
                    .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                            failure -> assertThat(failure.getStatusCode().value()).isEqualTo(400));
            assertThat(jdbc.queryForObject("select count(*) from saas_sync_event where event_id = ?", Long.class, request.eventId())).isZero();
        }
        Map<String, Object> future = applicationPayload(site, source, UUID.randomUUID(), 1, 1);
        future.put("lastSeenAt", Instant.now().plusSeconds(3600).toString());
        assertThatThrownBy(() -> sync.receive(request(site, source, future), site.token())).hasMessageContaining("Informe operativo invalido");
        Map<String, Object> boundary = applicationPayload(site, source, UUID.randomUUID(), 1, 1);
        boundary.put("firstSeenAt", Instant.EPOCH.toString());
        sync.receive(request(site, source, boundary), site.token());
        assertThat(rows(site)).singleElement().satisfies(row -> assertThat(row.firstSeenAt()).isEqualTo(Instant.EPOCH));
    }

    private java.util.List<StoreFailureView> search(Site site, String trace) {
        return queries.page(new StoreFailureQueryService.Filter(site.company().getId(), site.store().getId(),
                site.installation().getInstallationId(), null, null, null, null, false, trace), null, 50).items();
    }
    private Map<String, Object> applicationPayload(Site site, UUID source, UUID trace, long revision, long count) {
        Map<String, Object> value = payload(site, source, revision, "OPEN", count);
        value.put("schemaVersion", 2); value.put("source", "LOCAL_APPLICATION");
        value.put("severity", "DANGER"); value.put("code", "APPLICATION_ERROR");
        value.put("module", "SALES"); value.put("appVersion", "0.0.1-SNAPSHOT");
        value.put("traceId", trace.toString()); value.put("exceptionType", "java.lang.IllegalStateException");
        value.put("errorLocation", "com.tpverp.backend.sales.SaleService.save:123");
        return value;
    }
    private java.util.List<StoreFailureView> rows(Site site) { return queries.page(filter(site.company().getId(), false), null, 50).items(); }
    private StoreFailureQueryService.Filter filter(UUID company, boolean active) {
        return new StoreFailureQueryService.Filter(company, null, null, null, null, null, null, active, null);
    }
    private SyncEventRequest request(Site site, UUID source, Map<String, Object> payload) {
        return new SyncEventRequest(UUID.randomUUID(), site.company().getId(), site.store().getId(), null,
                StoreFailureProjector.ENTITY_TYPE, source, SyncOperation.ACTUALIZAR, payload);
    }
    private Map<String, Object> payload(Site site, UUID source, long revision, String status, long count) {
        // Fixed source dates keep transport duplicates canonical.
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 1); value.put("installationId", site.installation().getInstallationId().toString());
        value.put("source", "LOCAL_SYNC"); value.put("sourceId", source.toString()); value.put("sourceRevision", revision);
        value.put("status", status); value.put("severity", "WARNING"); value.put("code", "SYNC_DELIVERY_FAILED");
        value.put("firstSeenAt", "2026-01-01T00:00:00Z"); value.put("lastSeenAt", "2026-01-01T00:01:00Z"); value.put("occurrences", count);
        return value;
    }
    private void insert(Site site, Instant at, String status) {
        jdbc.update("""
                insert into saas_store_failure
                (id,company_id,store_id,installation_id,source,source_id,source_revision,status,severity,code,first_seen_at,last_seen_at,received_at,occurrences)
                values (?,?,?,?,'LOCAL_SYNC',?,1,?,'WARNING','SYNC_DELIVERY_FAILED',?,?,?,1)
                """, UUID.randomUUID(), site.company().getId(), site.store().getId(), site.installation().getId(), UUID.randomUUID(), status,
                Timestamp.from(at), Timestamp.from(at), Timestamp.from(at));
    }
    private Site site() {
        SaasCompany company = companies.saveAndFlush(new SaasCompany(UUID.randomUUID(), "Supervision test",
                validCif("B" + COMPANY.getAndIncrement() + "0"), TaxpayerType.SOCIEDAD, TaxRegime.IVA, Instant.now()));
        SaasStore store = stores.saveAndFlush(new SaasStore(UUID.randomUUID(), company, "001", "Supervision store", "Atlantic/Canary", Instant.now()));
        SaasLicense license = licenses.saveAndFlush(new SaasLicense(UUID.randomUUID(), company, "SUPERVISION-" + UUID.randomUUID(),
                Instant.now().plusSeconds(86400), 1, 1, Instant.now()));
        String token = tokens.newToken();
        SaasInstallation installation = installations.saveAndFlush(new SaasInstallation(UUID.randomUUID(), company, store, license,
                UUID.randomUUID(), "SUPERVISION-INSTALLATION", null, tokens.hash(token), Instant.now()));
        return new Site(company, store, installation, token);
    }
    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
    private record Site(SaasCompany company, SaasStore store, SaasInstallation installation, String token) { }
}

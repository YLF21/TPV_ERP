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

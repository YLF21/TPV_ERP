package com.tpverp.saas.document;

import static com.tpverp.saas.SaasTestData.validCif;
import static com.tpverp.saas.document.CommercialDocumentRecoveryApi.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.*;
import com.tpverp.saas.sync.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Synthetic, committed fixtures only. Explicit test database plus a dedicated Flyway schema. */
@SpringBootTest(properties = {
        "spring.flyway.default-schema=commercial_document_recovery_test",
        "spring.datasource.hikari.schema=commercial_document_recovery_test",
        "spring.jpa.properties.hibernate.default_schema=commercial_document_recovery_test"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TPV_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class CommercialDocumentRecoveryPostgreSqlTest {
    private static final AtomicInteger COMPANY_NUMBER = new AtomicInteger(9700000);
    private static final String ROUTE = "/api/v1/commercial-document-queries/recovery-status";
    @Autowired CommercialDocumentRecoveryService recovery;
    @Autowired SyncEventService sync;
    @Autowired SaasCompanyRepository companies;
    @Autowired SaasStoreRepository stores;
    @Autowired SaasLicenseRepository licenses;
    @Autowired SaasInstallationRepository installations;
    @Autowired TokenHasher tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @BeforeEach void onlyUsesDedicatedTestSchema() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo("commercial_document_recovery_test");
    }

    @Test void preflightPreservesOrderAndReportsOnlyExistingOwnProjectionWithoutWrites() {
        Site site = site(); UUID document = UUID.randomUUID(); UUID missing = UUID.randomUUID();
        UUID event = receive(site, document, snapshot(1, null));
        var before = counts(site);
        var response = recovery.status(new Request(site.company().getId(), site.store().getId(),
                List.of(new Document(missing, null, null), new Document(document, null, null))), site.token());
        assertThat(response.companyId()).isEqualTo(site.company().getId());
        assertThat(response.storeId()).isEqualTo(site.store().getId());
        assertThat(response.installationId()).isEqualTo(site.installation().getId());
        assertThat(response.schemaVersion()).isEqualTo(2);
        assertThat(response.documents()).containsExactly(
                new DocumentStatus(missing, Status.MISSING, null, null, EventStatus.NOT_REQUESTED, false, null, null, null),
                new DocumentStatus(document, Status.PROJECTED, 1L, event, EventStatus.NOT_REQUESTED, false, null, "9007199254740993.01", "EUR"));
        assertThat(counts(site)).isEqualTo(before);
    }

    @Test void verifiesHistoricalAndIdempotentEventsAgainstLedgerNotJustLatestHeader() {
        Site site = site(); UUID document = UUID.randomUUID();
        UUID first = receive(site, document, snapshot(1, null));
        UUID latest = receive(site, document, snapshot(3, null));
        UUID late = receive(site, document, snapshot(2, null));
        UUID duplicateRevision = receive(site, document, snapshot(1, null));
        for (var pair : Map.of(first, 1L, latest, 3L, late, 2L, duplicateRevision, 1L).entrySet()) {
            var result = inspect(site, document, pair.getKey(), pair.getValue());
            assertThat(result.currentRevision()).isEqualTo(3L);
            assertThat(result.currentEventId()).isEqualTo(latest);
            assertThat(result.requestedEventStatus()).isEqualTo(EventStatus.PROJECTED);
            assertThat(result.requestedRevisionRecorded()).isTrue();
        }
        assertThat(inspect(site, document, first, 3L).requestedRevisionRecorded()).isFalse();
        assertThat(inspect(site, document, first, 4L).requestedRevisionRecorded()).isFalse();
        assertThat(inspect(site, document, UUID.randomUUID(), 3L).requestedRevisionRecorded()).isFalse();
    }

    @ParameterizedTest @EnumSource(value = SaasSyncEvent.ProjectionStatus.class, names = {"RECEIVED", "IGNORED", "ERROR"})
    void acknowledgedOrFailedEventIsNotARecordedRevision(SaasSyncEvent.ProjectionStatus state) {
        Site site = site(); UUID document = UUID.randomUUID();
        UUID event = receive(site, document, snapshot(1, null));
        // Model historic ACKs/partial data explicitly: neither a header nor ledger may override event state.
        jdbc.update("update saas_sync_event set projection_status=? where event_id=?", state.name(), event);
        var before = counts(site);
        var result = inspect(site, document, event, 1L);
        assertThat(result.status()).isEqualTo(Status.PROJECTED);
        assertThat(result.requestedEventStatus().name()).isEqualTo(state.name());
        assertThat(result.requestedRevisionRecorded()).isFalse();
        assertThat(counts(site)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select projection_status from saas_sync_event where event_id=?", String.class, event)).isEqualTo(state.name());
    }

    @Test void legacyAckRemainsIgnoredAndDoesNotCreateCommercialProjection() {
        Site site = site(); UUID document = UUID.randomUUID();
        var legacy = snapshot(1, null); legacy.remove("schemaVersion"); legacy.remove("sourceRevision");
        UUID event = receive(site, document, legacy);
        assertThat(inspect(site, document, event, 1L)).isEqualTo(new DocumentStatus(document, Status.MISSING,
                null, null, EventStatus.IGNORED, false, null, null, null));
    }

    @Test void requiresMatchingLedgerHashEventPayloadVersionAndRevision() {
        Site site = site(); UUID document = UUID.randomUUID();
        UUID event = receive(site, document, snapshot(1, null));
        String originalPayload = jdbc.queryForObject("select payload from saas_sync_event where event_id=?", String.class, event);
        for (String invalid : List.of("{\"schemaVersion\":1,\"sourceRevision\":1}",
                "{\"schemaVersion\":2,\"sourceRevision\":2}", "{\"schemaVersion\":2,\"sourceRevision\":\"1\"}")) {
            jdbc.update("update saas_sync_event set payload=? where event_id=?", invalid, event);
            assertThat(inspect(site, document, event, 1L).requestedRevisionRecorded()).isFalse();
        }
        jdbc.update("update saas_sync_event set payload=? where event_id=?", originalPayload, event);
        assertThat(inspect(site, document, event, 1L).requestedRevisionRecorded()).isTrue();
        jdbc.update("update saas_commercial_document_revision set source_payload_hash=repeat('0',64) where source_event_id=?", event);
        assertThat(inspect(site, document, event, 1L).requestedRevisionRecorded()).isFalse();
        jdbc.update("delete from saas_commercial_document_revision where source_event_id=?", event);
        assertThat(inspect(site, document, event, 1L).requestedRevisionRecorded()).isFalse();
    }

    @Test void rejectsWrongDocumentStoreCompanyAndLedgerEventOriginEvenWhenIdsAreKnown() {
        Site owner = site(); Site otherStore = site(owner.company(), "002"); Site foreign = site();
        UUID document = UUID.randomUUID(); UUID event = receive(owner, document, snapshot(1, null));
        UUID secondDocument = UUID.randomUUID();
        UUID secondEvent = receive(owner, secondDocument, snapshot(1, null));
        assertThat(inspect(owner, document, secondEvent, 1L).requestedEventStatus()).isEqualTo(EventStatus.MISSING);
        assertThat(inspect(otherStore, document, event, 1L)).isEqualTo(new DocumentStatus(document,
                Status.MISSING, null, null, EventStatus.MISSING, false, null, null, null));
        assertThat(inspect(foreign, document, event, 1L)).isEqualTo(new DocumentStatus(document,
                Status.MISSING, null, null, EventStatus.MISSING, false, null, null, null));
        // The FK alone allows a ledger to reference another event. The read must check its whole provenance.
        jdbc.update("update saas_commercial_document_revision set source_event_id=? where source_event_id=?", secondEvent, event);
        assertThat(inspect(owner, document, event, 1L).requestedRevisionRecorded()).isFalse();
    }

    @Test void replacementInstallationCannotReadFormerOwnerHeaderOrEventDetails() {
        Site former = site(); UUID document = UUID.randomUUID();
        UUID event = receive(former, document, snapshot(1, UUID.randomUUID()));
        revoke(former);
        Site replacement = installation(former.company(), former.store());
        assertThat(inspect(replacement, document, event, 1L)).isEqualTo(new DocumentStatus(document,
                Status.OTHER_INSTALLATION, null, null, EventStatus.MISSING, false, null, null, null));
        assertThat(inspect(replacement, document, null, null)).isEqualTo(new DocumentStatus(document,
                Status.OTHER_INSTALLATION, null, null, EventStatus.NOT_REQUESTED, false, null, null, null));
    }

    @Test void customerLinkedRequiresExactInstallationAndCompanyAndSupportsLateBinding() {
        Site owner = site(); Site otherStore = site(owner.company(), "002");
        UUID local = UUID.randomUUID(); UUID document = UUID.randomUUID();
        UUID event = receive(owner, document, snapshot(1, local));
        UUID customer = customer(owner.company());
        link(otherStore, local, customer);
        assertThat(inspect(owner, document, event, 1L).customerLinked()).isFalse();
        link(owner, local, customer);
        assertThat(inspect(owner, document, event, 1L).customerLinked()).isTrue();
        UUID anonymous = UUID.randomUUID(); UUID anonymousEvent = receive(owner, anonymous, snapshot(1, null));
        assertThat(inspect(owner, anonymous, anonymousEvent, 1L).customerLinked()).isNull();
    }

    @Test void httpAuthenticationIsStoreBoundAndRevokedTokensFail() throws Exception {
        Site owner = site(); Site other = site(owner.company(), "002");
        var request = new Request(owner.company().getId(), owner.store().getId(), List.of(new Document(UUID.randomUUID(), null, null)));
        String json = mapper.writeValueAsString(request);
        mvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isUnauthorized());
        mvc.perform(post(ROUTE).header("X-TPV-Installation-Token", other.token()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(ROUTE).header("X-TPV-Installation-Token", owner.token()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value(2)).andExpect(jsonPath("$.documents[0].status").value("MISSING"));
        revoke(owner);
        mvc.perform(post(ROUTE).header("X-TPV-Installation-Token", owner.token()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest @ValueSource(strings = {"1.0", "1e0", "1.0000000000000001", "\"1\"", "-1", "9223372036854775808"})
    void realHttpRejectsNonIntegralOrOutOfRangeRevision(String revision) throws Exception {
        Site site = site();
        String json = "{\"companyId\":\"" + site.company().getId() + "\",\"storeId\":\"" + site.store().getId()
                + "\",\"documents\":[{\"documentId\":\"" + UUID.randomUUID() + "\",\"eventId\":\"" + UUID.randomUUID()
                + "\",\"sourceRevision\":" + revision + "}]}";
        mvc.perform(post(ROUTE).header("X-TPV-Installation-Token", site.token()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
    }

    private DocumentStatus inspect(Site site, UUID document, UUID event, Long revision) {
        return recovery.status(new Request(site.company().getId(), site.store().getId(),
                List.of(new Document(document, event, revision))), site.token()).documents().getFirst();
    }
    private UUID receive(Site site, UUID document, Map<String, Object> payload) {
        UUID event = UUID.randomUUID();
        sync.receive(new SyncEventRequest(event, site.company().getId(), site.store().getId(), null,
                "DOCUMENTO", document, SyncOperation.ACTUALIZAR, payload), site.token());
        return event;
    }
    private static Map<String, Object> snapshot(long revision, UUID customer) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("schemaVersion", 2); payload.put("sourceRevision", revision);
        payload.put("tipo", "TICKET"); payload.put("estado", "PAGADO"); payload.put("numero", "RECOVERY-001");
        payload.put("fecha", "2024-02-29"); payload.put("subtotal", "9007199254740993.00");
        payload.put("impuestos", "0.01"); payload.put("total", "9007199254740993.01"); payload.put("moneda", "EUR");
        if (customer != null) payload.put("clienteId", customer.toString());
        return payload;
    }
    private List<Long> counts(Site site) {
        return List.of("saas_sync_event", "saas_commercial_document", "saas_commercial_document_revision").stream()
                .map(table -> jdbc.queryForObject("select count(*) from " + table + " where company_id=?", Long.class, site.company().getId())).toList();
    }
    private UUID customer(SaasCompany company) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into saas_erp_customer(id,company_id,code,name,tax_id,document_type,active,created_at) values(?,?,?,'Recovery customer',?,'PASAPORTE',true,now())",
                id, company.getId(), "C-" + id, "TEST-" + id);
        return id;
    }
    private void link(Site site, UUID local, UUID central) {
        jdbc.update("insert into saas_customer_identity_link(installation_id,local_customer_id,company_id,customer_id) values(?,?,?,?)",
                site.installation().getId(), local, site.company().getId(), central);
    }
    private void revoke(Site site) {
        jdbc.update("update saas_installation set active=false, revoked_at=now(), revoked_by='recovery-test', revocation_reason='Synthetic replacement' where id=?",
                site.installation().getId());
    }
    private Site site() {
        var company = companies.saveAndFlush(new SaasCompany(UUID.randomUUID(), "Recovery test company",
                validCif("B" + COMPANY_NUMBER.getAndIncrement() + "0"), TaxpayerType.SOCIEDAD, TaxRegime.IVA, Instant.now()));
        return site(company, "001");
    }
    private Site site(SaasCompany company, String code) {
        return installation(company, stores.saveAndFlush(new SaasStore(UUID.randomUUID(), company, code, "Recovery test store", "Atlantic/Canary", Instant.now())));
    }
    private Site installation(SaasCompany company, SaasStore store) {
        var license = licenses.saveAndFlush(new SaasLicense(UUID.randomUUID(), company, "RECOVERY-" + UUID.randomUUID(), Instant.now().plusSeconds(86400), 1, 1, Instant.now()));
        String token = tokens.newToken();
        var installation = installations.saveAndFlush(new SaasInstallation(UUID.randomUUID(), company, store, license,
                UUID.randomUUID(), "RECOVERY-TEST", null, tokens.hash(token), Instant.now()));
        return new Site(company, store, installation, token);
    }
    private record Site(SaasCompany company, SaasStore store, SaasInstallation installation, String token) { }
}

package com.tpverp.saas.document;

import static com.tpverp.saas.SaasTestData.validCif;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.*;
import com.tpverp.saas.sync.*;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Committed fixtures stay in a dedicated schema of the isolated test database. */
@SpringBootTest(properties = {
        "spring.flyway.default-schema=commercial_document_test",
        "spring.datasource.hikari.schema=commercial_document_test",
        "spring.jpa.properties.hibernate.default_schema=commercial_document_test"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommercialDocumentSyncPostgreSqlTest {
    private static final AtomicInteger COMPANY_NUMBER = new AtomicInteger(9200000);
    @Autowired SyncEventService sync;
    @Autowired SaasSyncEventRepository events;
    @Autowired SaasCompanyRepository companies;
    @Autowired SaasStoreRepository stores;
    @Autowired SaasLicenseRepository licenses;
    @Autowired SaasInstallationRepository installations;
    @Autowired TokenHasher tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void assertIsolatedSchema() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class))
                .isEqualTo("commercial_document_test");
    }

    @Test
    void httpProjectsHistoricalValuesAndAuthenticatedOriginWithoutInventingCustomerIdentity() throws Exception {
        Site site = site();
        UUID documentId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID creator = UUID.randomUUID();
        UUID confirmer = UUID.randomUUID();
        UUID terminal = UUID.randomUUID();
        Map<String, Object> data = payload(0, "PENDIENTE", "12.34");
        data.put("clienteId", customerId.toString());
        data.put("creadoPor", creator.toString());
        data.put("confirmadoPor", confirmer.toString());
        data.put("terminalOrigenId", terminal.toString());
        data.put("creadoEn", "2025-12-31T23:55:00Z");
        data.put("confirmadoEn", "2026-01-01T00:05:00Z");
        // An untrusted duplicate scope in payload must never control the projection key.
        data.put("companyId", UUID.randomUUID().toString());
        data.put("storeId", UUID.randomUUID().toString());
        SyncEventRequest request = request(site, documentId, SyncOperation.CONFIRMAR, data);
        mvc.perform(post("/api/v1/sync/events").header("X-TPV-Installation-Token", site.token())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk());

        Map<String, Object> row = row(site, documentId);
        assertThat(row).containsEntry("source_event_id", request.eventId())
                .containsEntry("source_installation_id", site.installation().getId())
                .containsEntry("company_id", site.company().getId())
                .containsEntry("store_id", site.store().getId())
                .containsEntry("source_revision", 0L).containsEntry("schema_version", 2)
                .containsEntry("business_date", Date.valueOf("2025-12-31"))
                .containsEntry("customer_local_id", customerId)
                .containsEntry("created_by_local_id", creator)
                .containsEntry("confirmed_by_local_id", confirmer)
                .containsEntry("origin_terminal_local_id", terminal);
        assertThat((BigDecimal) row.get("total")).isEqualByComparingTo("12.34");
        assertThat((BigDecimal) row.get("tax_total")).isEqualByComparingTo("2.34");
        assertThat(events.findById(request.eventId()).orElseThrow().getProjectionStatus())
                .isEqualTo(SaasSyncEvent.ProjectionStatus.PROJECTED);
        assertThat(jdbc.queryForObject("select count(*) from saas_erp_customer where company_id = ?",
                Integer.class, site.company().getId())).isZero();
    }

    @Test
    void paymentUpdatesAndRetriesKeepOneDocumentAndOlderEventsCannotUndoCancellation() {
        Site site = site();
        UUID id = UUID.randomUUID();
        receive(site, id, 1, "PENDIENTE", "20.00");
        receive(site, id, 2, "PAGADO", "20.00");
        Map<String, Object> cancelled = payload(3, "ANULADO", "20.00");
        SyncEventRequest last = request(site, id, SyncOperation.ANULAR, cancelled);
        sync.receive(last, site.token());
        sync.receive(last, site.token()); // Same event retry.
        sync.receive(request(site, id, SyncOperation.ANULAR, cancelled), site.token()); // Same snapshot, new event.
        receive(site, id, 1, "PENDIENTE", "20.00");

        assertThat(count(site)).isEqualTo(1);
        assertThat(row(site, id)).containsEntry("source_revision", 3L)
                .containsEntry("document_status", "ANULADO").containsEntry("source_event_id", last.eventId());
        assertThat((BigDecimal) row(site, id).get("total")).isEqualByComparingTo("20.00");
    }

    @Test
    void sameRevisionWithChangedContentRollsBackEventAndDoesNotOverwriteSnapshot() {
        Site site = site();
        UUID id = UUID.randomUUID();
        receive(site, id, 4, "PAGADO", "10.00");
        Map<String, Object> before = row(site, id);
        SyncEventRequest conflicting = request(site, id, SyncOperation.ACTUALIZAR, payload(4, "PAGADO", "11.00"));
        assertThatThrownBy(() -> sync.receive(conflicting, site.token()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        assertThat(events.existsById(conflicting.eventId())).isFalse();
        assertThat(row(site, id)).isEqualTo(before);
    }

    @Test
    void customerAndDocumentUuidsAreScopedToCompanyAndStore() {
        Site first = site();
        Site otherStore = site(first.company(), "002");
        Site otherCompany = site();
        UUID id = UUID.randomUUID();
        receive(first, id, 1, "PAGADO", "10.00");
        receive(otherStore, id, 1, "PAGADO", "20.00");
        receive(otherCompany, id, 1, "PAGADO", "30.00");
        assertThat((BigDecimal) row(first, id).get("total")).isEqualByComparingTo("10.00");
        assertThat((BigDecimal) row(otherStore, id).get("total")).isEqualByComparingTo("20.00");
        assertThat((BigDecimal) row(otherCompany, id).get("total")).isEqualByComparingTo("30.00");
    }

    @Test
    void projectsMetadataAndRelationsWithinAuthenticatedScopeBeforeTheirOriginArrives() {
        Site first = site();
        Site otherStore = site(first.company(), "002");
        Site otherCompany = site();
        UUID id = UUID.randomUUID();
        UUID origin = UUID.randomUUID();
        UUID cancelledBy = UUID.randomUUID();
        Map<String, Object> data = payload(1, "ANULADO", "12.34");
        data.put("anuladoPor", cancelledBy.toString());
        data.put("anuladoEn", "2026-09-10T12:45:01.123456Z");
        data.put("fechaVencimiento", "2026-10-01");
        data.put("settledByOrigin", false);
        data.put("relaciones", List.of(relation("FACTURA_DE", origin), relation("COMPENSA", origin)));
        sync.receive(request(first, id, SyncOperation.ANULAR, data), first.token());
        for (Site other : List.of(otherStore, otherCompany)) {
            var otherData = payload(1, "PAGADO", "30.00");
            otherData.put("relaciones", List.of(relation("RECTIFICA", origin)));
            sync.receive(request(other, id, SyncOperation.CONFIRMAR, otherData), other.token());
            assertThat(relations(other, id)).containsExactly(relationRow("RECTIFICA", origin));
        }

        Map<String, Object> projected = row(first, id);
        assertThat(projected).containsEntry("cancelled_by_local_id", cancelledBy)
                .containsEntry("due_date", Date.valueOf("2026-10-01"))
                .containsEntry("settled_by_origin", false).containsEntry("relationships_complete", true);
        assertThat(((Timestamp) projected.get("source_cancelled_at")).toInstant())
                .isEqualTo(Instant.parse("2026-09-10T12:45:01.123456Z"));
        assertThat(relations(first, id)).containsExactlyInAnyOrder(
                relationRow("FACTURA_DE", origin), relationRow("COMPENSA", origin));
        assertThat(count(first)).isEqualTo(1); // No placeholder document was invented for the missing origin.
        receive(first, origin, 1, "PAGADO", "12.34");
        assertThat(count(first)).isEqualTo(2);
        assertThat(relations(first, id)).containsExactlyInAnyOrder(
                relationRow("FACTURA_DE", origin), relationRow("COMPENSA", origin));
    }

    @Test
    void onlyANewerFullSnapshotCanReplaceMetadataAndRelationsIncludingUnknownAndEmptyStates() {
        Site site = site();
        UUID id = UUID.randomUUID();
        UUID origin = UUID.randomUUID();
        var latest = payload(2, "PAGADO", "20.00");
        latest.put("anuladoPor", UUID.randomUUID().toString());
        latest.put("anuladoEn", "2026-09-10T12:45:00Z");
        latest.put("fechaVencimiento", "2026-10-01");
        latest.put("settledByOrigin", true);
        latest.put("relaciones", List.of(relation("FACTURA_DE", origin)));
        var last = request(site, id, SyncOperation.ACTUALIZAR, latest);
        sync.receive(last, site.token());
        var before = row(site, id);
        var old = payload(1, "PENDIENTE", "20.00");
        old.put("settledByOrigin", false);
        old.put("relaciones", List.of(relation("RECTIFICA", UUID.randomUUID())));
        sync.receive(request(site, id, SyncOperation.ACTUALIZAR, old), site.token());
        sync.receive(last, site.token());
        sync.receive(request(site, id, SyncOperation.ACTUALIZAR, latest), site.token());
        assertThat(row(site, id)).isEqualTo(before);
        assertThat(relations(site, id)).containsExactly(relationRow("FACTURA_DE", origin));

        var conflicting = new LinkedHashMap<>(latest);
        conflicting.put("relaciones", List.of());
        var conflict = request(site, id, SyncOperation.ACTUALIZAR, conflicting);
        assertThatThrownBy(() -> sync.receive(conflict, site.token()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        assertThat(events.existsById(conflict.eventId())).isFalse();
        assertThat(row(site, id)).isEqualTo(before);
        assertThat(relations(site, id)).containsExactly(relationRow("FACTURA_DE", origin));

        UUID replacementOrigin = UUID.randomUUID();
        var replacement = payload(3, "PAGADO", "20.00");
        replacement.put("relaciones", List.of(relation("COMPENSA", replacementOrigin)));
        replacement.put("settledByOrigin", false);
        sync.receive(request(site, id, SyncOperation.ACTUALIZAR, replacement), site.token());
        assertThat(relations(site, id)).containsExactly(relationRow("COMPENSA", replacementOrigin));
        assertThat(row(site, id)).containsEntry("settled_by_origin", false);

        receive(site, id, 4, "PAGADO", "20.00"); // Missing metadata replaces old values with unknown, not guesses.
        assertThat(row(site, id)).containsEntry("relationships_complete", false)
                .containsEntry("settled_by_origin", null).containsEntry("cancelled_by_local_id", null)
                .containsEntry("source_cancelled_at", null).containsEntry("due_date", null);
        assertThat(relations(site, id)).isEmpty();
        var empty = payload(5, "PAGADO", "20.00");
        empty.put("relaciones", List.of());
        sync.receive(request(site, id, SyncOperation.ACTUALIZAR, empty), site.token());
        assertThat(row(site, id)).containsEntry("relationships_complete", true);
        assertThat(relations(site, id)).isEmpty();
        var unknown = payload(6, "PAGADO", "20.00");
        unknown.put("relaciones", null);
        sync.receive(request(site, id, SyncOperation.ACTUALIZAR, unknown), site.token());
        assertThat(row(site, id)).containsEntry("relationships_complete", false);
        assertThat(relations(site, id)).isEmpty();
    }

    @Test
    void malformedRelationsRollBackTheirEventAndCannotReplaceAnExistingSnapshot() throws Exception {
        Site site = site();
        UUID id = UUID.randomUUID();
        UUID origin = UUID.randomUUID();
        var valid = payload(1, "PAGADO", "20.00");
        valid.put("relaciones", List.of(relation("COMPENSA", origin)));
        sync.receive(request(site, id, SyncOperation.CONFIRMAR, valid), site.token());
        var before = row(site, id);
        for (Object invalid : List.of("[]", List.of(relation("FACTURA_DE", id)),
                List.of(relation("COMPENSA", origin), relation("COMPENSA", origin)))) {
            var data = payload(2, "PAGADO", "20.00");
            data.put("relaciones", invalid);
            var request = request(site, id, SyncOperation.ACTUALIZAR, data);
            mvc.perform(post("/api/v1/sync/events").header("X-TPV-Installation-Token", site.token())
                            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request)))
                    .andExpect(status().isBadRequest());
            assertThat(events.existsById(request.eventId())).isFalse();
            assertThat(row(site, id)).isEqualTo(before);
            assertThat(relations(site, id)).containsExactly(relationRow("COMPENSA", origin));
        }
    }

    @Test
    void httpRejectsMissingForeignAndRevokedInstallationTokensWithoutSavingEvents() throws Exception {
        Site owner = site();
        Site otherStore = site(owner.company(), "002");
        Site otherCompany = site();
        SyncEventRequest request = request(owner, UUID.randomUUID(), SyncOperation.CONFIRMAR, payload(0, "PAGADO", "1.00"));
        byte[] body = mapper.writeValueAsBytes(request);
        mvc.perform(post("/api/v1/sync/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        for (String token : List.of("invalid-token", otherStore.token(), otherCompany.token())) {
            mvc.perform(post("/api/v1/sync/events").header("X-TPV-Installation-Token", token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }
        owner.installation().revoke(Instant.now(), "test", "test revocation");
        installations.saveAndFlush(owner.installation());
        mvc.perform(post("/api/v1/sync/events").header("X-TPV-Installation-Token", owner.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        assertThat(events.existsById(request.eventId())).isFalse();
        assertThat(count(owner)).isZero();
    }

    @Test
    void differentInstallationCannotTakeOverSameDocumentWithHigherRevision() {
        Site owner = site();
        UUID id = UUID.randomUUID();
        receive(owner, id, 1, "PAGADO", "10.00");
        // Only one installation can be active per store: simulate an actual replacement.
        owner.installation().revoke(Instant.now(), "test", "test replacement");
        installations.saveAndFlush(owner.installation());
        Site other = installation(owner.company(), owner.store());
        SyncEventRequest request = request(other, id, SyncOperation.ACTUALIZAR, payload(2, "PAGADO", "99.00"));
        assertThatThrownBy(() -> sync.receive(request, other.token()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        assertThat(events.existsById(request.eventId())).isFalse();
        assertThat(row(owner, id)).containsEntry("source_installation_id", owner.installation().getId())
                .containsEntry("source_revision", 1L);
    }

    @Test
    void malformedVersionedRequestCanBeCorrectedAndRetriedWithoutPoisoningEventId() throws Exception {
        Site site = site();
        UUID documentId = UUID.randomUUID();
        Map<String, Object> data = payload(0, "PAGADO", "1.001");
        SyncEventRequest request = request(site, documentId, SyncOperation.CONFIRMAR, data);
        mvc.perform(post("/api/v1/sync/events").header("X-TPV-Installation-Token", site.token())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest());
        assertThat(events.existsById(request.eventId())).isFalse();
        assertThat(count(site)).isZero();
        data.put("total", "1.00");
        mvc.perform(post("/api/v1/sync/events").header("X-TPV-Installation-Token", site.token())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk());
        assertThat(count(site)).isEqualTo(1);
    }

    @Test
    void httpRejectsFloatingPointJsonBeforeItCanLoseMoneyOrRevisionPrecision() throws Exception {
        Site site = site();
        UUID id = UUID.randomUUID();
        var data = payload(0, "PAGADO", "1.00");
        var request = request(site, id, SyncOperation.CONFIRMAR, data);
        String json = mapper.writeValueAsString(request);
        for (String invalid : List.of(
                json.replace("\"total\":\"1.00\"", "\"total\":9007199254740993.01"),
                json.replace("\"sourceRevision\":0", "\"sourceRevision\":9007199254740993.0"),
                json.replace("\"schemaVersion\":2", "\"schemaVersion\":1.0000000000000001"))) {
            assertThat(invalid).isNotEqualTo(json);
            mvc.perform(post("/api/v1/sync/events").header("X-TPV-Installation-Token", site.token())
                            .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
            assertThat(events.existsById(request.eventId())).isFalse();
        }
        data.put("total", "9007199254740993.01");
        mvc.perform(post("/api/v1/sync/events").header("X-TPV-Installation-Token", site.token())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk());
        assertThat((BigDecimal) row(site, id).get("total")).isEqualByComparingTo("9007199254740993.01");
    }

    @Test
    void legacyEventsStayAvailableWithoutPretendingToHaveAnOrderedSnapshot() {
        Site site = site();
        for (Map<String, Object> data : List.of(Map.<String, Object>of("numero", "T-1"),
                Map.<String, Object>of("numero", "T-2", "schemaVersion", 1))) {
            var request = request(site, UUID.randomUUID(), SyncOperation.CONFIRMAR, data);
            sync.receive(request, site.token());
            assertThat(events.findById(request.eventId()).orElseThrow().getProjectionStatus())
                    .isEqualTo(SaasSyncEvent.ProjectionStatus.IGNORED);
        }
        assertThat(count(site)).isZero();
    }

    @Test
    void preservesAllCommercialTypesAndSignedCreditNoteAmounts() {
        Site site = site();
        for (String type : List.of("TICKET", "FACTURA_VENTA", "ALBARAN_VENTA", "RECTIFICATIVA_VENTA")) {
            UUID id = UUID.randomUUID();
            Map<String, Object> data = payload(1, "PAGADO", "-12.34");
            data.put("tipo", type);
            data.put("subtotal", "-10.00");
            data.put("impuestos", "-2.34");
            sync.receive(request(site, id, SyncOperation.CONFIRMAR, data), site.token());
            assertThat(row(site, id)).containsEntry("document_type", type);
            assertThat((BigDecimal) row(site, id).get("total")).isEqualByComparingTo("-12.34");
        }
        assertThat(count(site)).isEqualTo(4);
    }

    @Test
    void concurrentFirstSnapshotsOfDifferentRevisionsAlwaysRetainNewest() throws Exception {
        Site site = site();
        UUID id = UUID.randomUUID();
        UUID origin = UUID.randomUUID();
        var older = payload(1, "PENDIENTE", "10.00");
        older.put("relaciones", List.of(relation("RECTIFICA", UUID.randomUUID())));
        older.put("settledByOrigin", false);
        var newer = payload(2, "PAGADO", "20.00");
        newer.put("relaciones", List.of(relation("FACTURA_DE", origin)));
        newer.put("settledByOrigin", true);
        assertThat(concurrent(site,
                request(site, id, SyncOperation.ACTUALIZAR, older),
                request(site, id, SyncOperation.ACTUALIZAR, newer)))
                .containsExactlyInAnyOrder(200, 200);
        assertThat(count(site)).isEqualTo(1);
        assertThat(row(site, id)).containsEntry("source_revision", 2L).containsEntry("document_status", "PAGADO")
                .containsEntry("settled_by_origin", true).containsEntry("relationships_complete", true);
        assertThat(relations(site, id)).containsExactly(relationRow("FACTURA_DE", origin));
        assertThat((BigDecimal) row(site, id).get("total")).isEqualByComparingTo("20.00");
    }

    @Test
    void concurrentConflictingFirstSnapshotsCommitOnlyOneEventAndDocument() throws Exception {
        Site site = site();
        UUID id = UUID.randomUUID();
        SyncEventRequest first = request(site, id, SyncOperation.ACTUALIZAR, payload(1, "PAGADO", "10.00"));
        SyncEventRequest second = request(site, id, SyncOperation.ACTUALIZAR, payload(1, "PAGADO", "20.00"));
        assertThat(concurrent(site, first, second)).containsExactlyInAnyOrder(200, 409);
        assertThat(count(site)).isEqualTo(1);
        assertThat(events.existsById(first.eventId()) ^ events.existsById(second.eventId())).isTrue();
    }

    @Test
    void contradictoryOldRevisionsConflictEvenAfterANewerSnapshotWasReceivedFirst() {
        Site site = site();
        UUID id = UUID.randomUUID();
        receive(site, id, 2, "PAGADO", "20.00");
        receive(site, id, 1, "PENDIENTE", "10.00");
        receive(site, id, 1, "PENDIENTE", "10.00");
        var contradiction = request(site, id, SyncOperation.ACTUALIZAR, payload(1, "PENDIENTE", "99.00"));
        assertThatThrownBy(() -> sync.receive(contradiction, site.token()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        assertThat(events.existsById(contradiction.eventId())).isFalse();
        assertThat(row(site, id)).containsEntry("source_revision", 2L);
        assertThat((BigDecimal) row(site, id).get("total")).isEqualByComparingTo("20.00");
        assertThat(jdbc.queryForObject("select count(*) from saas_commercial_document_revision where company_id = ?",
                Integer.class, site.company().getId())).isEqualTo(2);
    }

    @Test
    void laterTransactionalFailureRollsBackBothNewAndUpdatedSnapshots() {
        Site site = site();
        UUID existingId = UUID.randomUUID();
        UUID origin = UUID.randomUUID();
        var initial = payload(1, "PENDIENTE", "10.00");
        initial.put("relaciones", List.of(relation("FACTURA_DE", origin)));
        sync.receive(request(site, existingId, SyncOperation.CONFIRMAR, initial), site.token());
        Map<String, Object> before = row(site, existingId);
        var changed = payload(2, "PAGADO", "20.00");
        changed.put("settledByOrigin", true);
        changed.put("relaciones", List.of(relation("COMPENSA", UUID.randomUUID())));
        SyncEventRequest update = request(site, existingId, SyncOperation.ACTUALIZAR, changed);
        var created = payload(1, "PAGADO", "30.00");
        created.put("relaciones", List.of(relation("RECTIFICA", origin)));
        SyncEventRequest create = request(site, UUID.randomUUID(), SyncOperation.CONFIRMAR, created);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            sync.receive(update, site.token());
            sync.receive(create, site.token());
            throw new IllegalStateException("Simulated failure after projection writes");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(row(site, existingId)).isEqualTo(before);
        assertThat(relations(site, existingId)).containsExactly(relationRow("FACTURA_DE", origin));
        assertThat(relations(site, create.entityId())).isEmpty();
        assertThat(count(site)).isEqualTo(1);
        assertThat(events.existsById(update.eventId())).isFalse();
        assertThat(events.existsById(create.eventId())).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from saas_commercial_document_revision where company_id = ?",
                Integer.class, site.company().getId())).isEqualTo(1);
    }

    private List<Integer> concurrent(Site site, SyncEventRequest... requests) throws Exception {
        var ready = new CountDownLatch(requests.length);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(requests.length)) {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (SyncEventRequest request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    try { sync.receive(request, site.token()); return 200; }
                    catch (ResponseStatusException error) { return error.getStatusCode().value(); }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = new ArrayList<Integer>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        }
    }

    private void receive(Site site, UUID id, long revision, String state, String total) {
        sync.receive(request(site, id, SyncOperation.ACTUALIZAR, payload(revision, state, total)), site.token());
    }

    private static SyncEventRequest request(Site site, UUID id, SyncOperation operation, Map<String, Object> payload) {
        return new SyncEventRequest(UUID.randomUUID(), site.company().getId(), site.store().getId(),
                null, "DOCUMENTO", id, operation, payload);
    }

    private static Map<String, Object> payload(long revision, String state, String total) {
        var data = new LinkedHashMap<String, Object>();
        data.put("schemaVersion", 2);
        data.put("sourceRevision", revision);
        data.put("tipo", "TICKET");
        data.put("numero", "001-251231-00001");
        data.put("estado", state);
        data.put("fecha", "2025-12-31");
        data.put("subtotal", "10.00");
        data.put("impuestos", "2.34");
        data.put("total", total);
        data.put("moneda", "EUR");
        return data;
    }

    private Map<String, Object> row(Site site, UUID id) {
        return jdbc.queryForMap("select * from saas_commercial_document where company_id = ? and store_id = ? and source_document_id = ?",
                site.company().getId(), site.store().getId(), id);
    }

    private static Map<String, Object> relation(String type, UUID origin) {
        return Map.of("tipo", type, "origenId", origin.toString());
    }

    private static Map<String, Object> relationRow(String type, UUID origin) {
        return Map.of("relation_type", type, "origin_document_id", origin);
    }

    private List<Map<String, Object>> relations(Site site, UUID id) {
        return jdbc.queryForList("""
                select relation_type, origin_document_id from saas_commercial_document_relation
                 where company_id = ? and store_id = ? and source_document_id = ?
                 order by relation_type, origin_document_id
                """, site.company().getId(), site.store().getId(), id);
    }

    private int count(Site site) {
        return jdbc.queryForObject("select count(*) from saas_commercial_document where company_id = ? and store_id = ?",
                Integer.class, site.company().getId(), site.store().getId());
    }

    private Site site() {
        SaasCompany company = companies.saveAndFlush(new SaasCompany(UUID.randomUUID(), "Document test company",
                validCif("B" + COMPANY_NUMBER.getAndIncrement() + "0"), TaxpayerType.SOCIEDAD,
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
                "DOCUMENT-TEST-" + UUID.randomUUID(), Instant.now().plusSeconds(86400), 1, 1, Instant.now()));
        String token = tokens.newToken();
        SaasInstallation installation = installations.saveAndFlush(new SaasInstallation(UUID.randomUUID(), company,
                store, license, UUID.randomUUID(), "DOCUMENT-TEST", null, tokens.hash(token), Instant.now()));
        return new Site(company, store, installation, token);
    }

    private record Site(SaasCompany company, SaasStore store, SaasInstallation installation, String token) {}
}

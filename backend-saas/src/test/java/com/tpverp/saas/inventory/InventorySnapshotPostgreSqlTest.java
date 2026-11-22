package com.tpverp.saas.inventory;

import static com.tpverp.saas.SaasTestData.validCif;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.admin.*;
import com.tpverp.saas.analytics.ReportStatistics.Period;
import com.tpverp.saas.license.*;
import com.tpverp.saas.movement.MovementReport;
import com.tpverp.saas.movement.MovementReportService;
import com.tpverp.saas.sync.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@SpringBootTest(
        properties = {
            "spring.flyway.default-schema=inventory_report_test",
            "spring.datasource.hikari.schema=inventory_report_test",
            "spring.jpa.properties.hibernate.default_schema=inventory_report_test"
        })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class InventorySnapshotPostgreSqlTest {
    static final AtomicInteger NUM = new AtomicInteger(9690000);
    @Autowired InventoryReportService reports;
    @Autowired InventoryComparisonService comparison;
    @Autowired MovementReportService movements;
    @Autowired SyncEventService sync;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired SaasCompanyRepository companies;
    @Autowired SaasStoreRepository stores;
    @Autowired SaasLicenseRepository licenses;
    @Autowired SaasInstallationRepository installations;
    @Autowired TokenHasher tokens;
    @Autowired MockMvc mvc;
    @Autowired SaasSessionTokenStore sessions;
    @Autowired SaasAdminUserRepository admins;

    @Test
    void fixtureRoundTripsExactlyAndTenantScopeCannotBeForged() throws Exception {
        var a = site();
        var b = site();
        var f = fixture(a);
        var req = request(a, f);
        assertThatThrownBy(() -> sync.receive(req, b.token)).hasMessageContaining("401");
        assertThat(sync.receive(req, a.token).projectionStatus())
                .isEqualTo(SaasSyncEvent.ProjectionStatus.PROJECTED);
        assertThat(sync.receive(req, a.token).projectionStatus())
                .isEqualTo(SaasSyncEvent.ProjectionStatus.PROJECTED);
        assertThat(sync.receive(request(a, f), a.token).projectionStatus())
                .isEqualTo(SaasSyncEvent.ProjectionStatus.PROJECTED);
        var balances =
                reports.balances(a.company(), a.store(), null, "731010", true, null, null, 0, 25);
        assertThat(balances.rows())
                .singleElement()
                .satisfies(row -> assertThat(row.quantity()).isEqualTo("9999999999999999.125"));
        assertThat(balances.snapshot().positiveCount()).isOne();
        assertThat(balances.snapshot().capturedAt())
                .isEqualTo(Instant.parse("2026-01-01T10:00:00.123456Z"));
        var same = fixture(b);
        same.put("balances", List.of());
        same.put("balanceCount", 0);
        sync.receive(request(b, same), b.token);
        assertThat(
                        reports.balances(
                                        b.company(), b.store(), null, null, null, null, null, 0, 25)
                                .total())
                .isZero();
        assertThatThrownBy(() -> reports.products(a.company(), b.store(), null, null, null, 0, 25))
                .hasMessageContaining("400");
        assertThat(reports.history(a.company(), a.store(), 0, 1).total()).isOne();
        assertThat(reports.products(a.company(), a.store(), null, "%", true, 0, 25).rows())
                .isEmpty();
        assertThat(reports.warehouses(a.company(), a.store(), null, 0, 25).rows()).hasSize(1);
        assertThat(reports.overview(a.company(), null, 9, 1).totals().positiveCount()).isOne();
    }

    @Test
    void partialChunksCanArriveOutOfOrderWhilePreviousCompleteRemainsVisible() throws Exception {
        var a = site();
        var previous = empty(a);
        previous.put("capturedAt", "2025-01-01T00:00:00Z");
        sync.receive(request(a, previous), a.token);
        var all = fixture(a);
        all.put("chunkCount", 2);
        var last = new HashMap<>(all);
        last.put("chunkIndex", 1);
        last.put("products", List.of());
        last.put("warehouses", List.of());
        sync.receive(request(a, last), a.token);
        assertThat(reports.history(a.company(), a.store(), 0, 10).total()).isOne();
        assertThat(
                        reports.overview(a.company(), null, 0, 10)
                                .rows()
                                .getFirst()
                                .snapshot()
                                .snapshotId()
                                .toString())
                .isEqualTo(previous.get("snapshotId"));
        assertThatThrownBy(
                        () -> reports.products(a.company(), a.store(), id(all), null, null, 0, 10))
                .hasMessageContaining("404");
        var conflict = new HashMap<>(last);
        conflict.put("capturedAt", "2024-01-01T00:00:00Z");
        rejected(a, conflict, "409");
        conflict = new HashMap<>(last);
        conflict.put(
                "balances",
                List.of(
                        Map.of(
                                "productId",
                                UUID.randomUUID().toString(),
                                "warehouseId",
                                "12b9dc65-f43e-49fe-9c1e-3fcba4f57db9",
                                "quantity",
                                "-1.001")));
        rejected(a, conflict, "409");
        var first = new HashMap<>(all);
        first.put("balances", List.of());
        sync.receive(request(a, first), a.token);
        var older = empty(a);
        older.put("capturedAt", "2024-01-01T00:00:00Z");
        sync.receive(request(a, older), a.token);
        assertThat(reports.history(a.company(), a.store(), 0, 10).rows())
                .extracting(InventoryReport.Snapshot::snapshotId)
                .containsExactly(id(all), id(previous), id(older));
        assertThat(reports.overview(a.company(), a.store(), 0, 10).totals().balances()).isOne();
    }

    @Test
    void failedValidationRollsBackAndBadReferencesNeverPublish() throws Exception {
        var a = site();
        List<Consumer<Map<String, Object>>> invalid =
                List.of(
                        f -> f.put("sourceInstallationId", UUID.randomUUID().toString()),
                        f -> f.put("capturedAt", "2099-01-01T00:00:00Z"),
                        f -> f.put("capturedAt", "2026-01-01T00:00:00.1234567Z"),
                        f -> f.put("schemaVersion", 2),
                        f -> f.put("chunkIndex", 1),
                        f -> f.put("extra", "unexpected"),
                        f -> f.put("products", Collections.nCopies(501, Map.of())),
                        f ->
                                f.put(
                                        "balances",
                                        List.of(
                                                Map.of(
                                                        "productId",
                                                        UUID.randomUUID().toString(),
                                                        "warehouseId",
                                                        UUID.randomUUID().toString(),
                                                        "quantity",
                                                        1))),
                        f ->
                                f.put(
                                        "balances",
                                        List.of(
                                                Map.of(
                                                        "productId",
                                                        UUID.randomUUID().toString(),
                                                        "warehouseId",
                                                        UUID.randomUUID().toString(),
                                                        "quantity",
                                                        "0.1234"))));
        for (var change : invalid) {
            var f = fixture(a);
            change.accept(f);
            rejected(a, f, "400");
        }
        var f = fixture(a);
        f.put("productCount", 2);
        rejected(a, f, "409");
        f.put("products", Collections.nCopies(2, ((List<?>) f.get("products")).getFirst()));
        rejected(a, f, "409");
        f = fixture(a);
        f.put("products", List.of());
        f.put("productCount", 0);
        rejected(a, f, "409");
        assertThat(reports.overview(a.company(), a.store(), 0, 10).rows().getFirst().snapshot())
                .isNull();
    }

    @Test
    void oldIgnoredEventReprojectsAndRelinkingRequiresNewSource() throws Exception {
        var a = site();
        var f = fixture(a);
        var req = request(a, f);
        String canonical =
                mapper.writer()
                        .with(
                                com.fasterxml.jackson.databind.SerializationFeature
                                        .ORDER_MAP_ENTRIES_BY_KEYS)
                        .writeValueAsString(f);
        var hash =
                HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                canonical.getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update(
                """
                insert into saas_sync_event(event_id,company_id,store_id,installation_id,entity_type,entity_id,operation,
                 payload,received_at,schema_version,payload_hash,projection_status)
                values (?,?,?,?,'INVENTORY_SNAPSHOT',?,'ACTUALIZAR',?,now(),1,?,'IGNORED')
                """,
                req.eventId(),
                a.company(),
                a.store(),
                a.install.getId(),
                id(f),
                canonical,
                hash);
        assertThat(sync.receive(req, a.token).projectionStatus())
                .isEqualTo(SaasSyncEvent.ProjectionStatus.PROJECTED);
        var old = a.install;
        old.revoke(Instant.now(), "test", "retired");
        installations.saveAndFlush(old);
        var b = installation(old.getCompany(), old.getStore(), old.getLicense());
        assertThat(reports.overview(a.company(), a.store(), 0, 25).rows().getFirst().snapshot())
                .isNull();
        assertThat(
                        reports.products(b.company(), b.store(), id(f), null, null, 0, 25)
                                .snapshot()
                                .currentInstallation())
                .isFalse();
        assertThat(
                        reports.history(a.company(), a.store(), 0, 10)
                                .rows()
                                .getFirst()
                                .currentInstallation())
                .isFalse();
        rejected(b, f, "400");
        var next = empty(b);
        sync.receive(request(b, next), b.token);
        assertThat(
                        reports.products(b.company(), b.store(), null, null, null, 0, 25)
                                .snapshot()
                                .snapshotId())
                .isEqualTo(id(next));
    }

    @Test
    void paginationAndClassificationIncludeUnmovedInactiveAndNegativePositions() throws Exception {
        var a = site();
        var f = fixture(a);
        var products = new ArrayList<Map<String, Object>>();
        var balances = new ArrayList<Map<String, Object>>();
        var warehouse = "12b9dc65-f43e-49fe-9c1e-3fcba4f57db9";
        for (int n = 0; n < 205; n++) {
            String id = UUID.randomUUID().toString();
            products.add(
                    Map.of(
                            "productId",
                            id,
                            "name",
                            "Product %03d".formatted(n),
                            "active",
                            n % 2 == 0,
                            "productType",
                            "UNIT"));
            if (n < 3)
                balances.add(
                        Map.of(
                                "productId",
                                id,
                                "warehouseId",
                                warehouse,
                                "quantity",
                                List.of("0.000", "-2.125", "1.000").get(n)));
        }
        f.put("products", products);
        f.put("productCount", products.size());
        f.put("balances", balances);
        f.put("balanceCount", balances.size());
        sync.receive(request(a, f), a.token);
        var rows = reports.products(a.company(), a.store(), null, "product", null, 1, 200);
        assertThat(rows.total()).isEqualTo(205);
        assertThat(rows.rows()).hasSize(5);
        assertThat(rows.rows().getFirst().name()).isEqualTo("Product 200");
        var summary = reports.overview(a.company(), a.store(), 1, 1);
        assertThat(summary.rows()).isEmpty();
        assertThat(summary.totals())
                .isEqualTo(new InventoryReport.Totals(1, 1, 205, 103, 1, 3, 1, 1, 1));
        assertThat(reports.products(a.company(), a.store(), null, null, false, 0, 20).total())
                .isEqualTo(102);
        assertThat(
                        reports.balances(
                                        a.company(),
                                        a.store(),
                                        null,
                                        null,
                                        false,
                                        null,
                                        UUID.fromString(warehouse),
                                        0,
                                        20)
                                .rows())
                .singleElement()
                .satisfies(row -> assertThat(row.quantity()).isEqualTo("-2.125"));
    }

    @Test
    void allStaffCanReadUncachedInventoryReports() throws Exception {
        var a = site();
        var reads = admin("VIEW_ADMIN_DATA");
        var reports = admin("VIEW_REPORTS");
        for (var route : List.of("overview", "history", "products", "balances", "warehouses")) {
            String url =
                    "/api/v1/admin/reports/inventory/"
                            + route
                            + "?companyId="
                            + a.company()
                            + "&storeId="
                            + a.store();
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).header("Authorization", reads)).andExpect(status().isOk());
            mvc.perform(get(url).header("Authorization", reports))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    @Test
    void comparesCompleteCapturesByCaptureTimeAndRetainsMissingBalancesAndZeroBaselines()
            throws Exception {
        var a = site();
        var b = site();
        var current = fixture(a);
        var prior = fixture(a);
        prior.put("snapshotId", UUID.randomUUID().toString());
        prior.put("capturedAt", "2025-01-01T00:00:00Z");
        String warehouse = "12b9dc65-f43e-49fe-9c1e-3fcba4f57db9";
        String changed = "beb8b102-9c02-4796-95c4-cf0d01e066c8";
        String added = UUID.randomUUID().toString(), removed = UUID.randomUUID().toString();
        String unchanged = UUID.randomUUID().toString(), zero = UUID.randomUUID().toString();
        for (var f : List.of(current, prior)) {
            boolean latest = f == current;
            var products = new ArrayList<Map<String, Object>>();
            var balances = new ArrayList<Map<String, String>>();
            for (String product : List.of(changed, latest ? added : removed, unchanged, zero)) {
                var item = new HashMap<String, Object>();
                ((Map<?, ?>) ((List<?>) f.get("products")).getFirst())
                        .forEach((k, v) -> item.put(k.toString(), v));
                item.put("productId", product);
                item.put("name", product.equals(changed) && latest ? "New name" : product);
                item.put("active", !latest || !product.equals(changed));
                products.add(item);
                String quantity =
                        product.equals(changed)
                                ? (latest ? "9999999999999999.125" : "9999999999999999.124")
                                : product.equals(zero) && latest ? "2.003" : "0.000";
                balances.add(
                        Map.of(
                                "productId",
                                product,
                                "warehouseId",
                                warehouse,
                                "quantity",
                                quantity));
            }
            f.put("products", products);
            f.put("balances", balances);
            f.put("balanceCount", products.size());
            f.put("productCount", products.size());
            sync.receive(request(a, f), a.token);
        }
        var result = compare(a, id(prior), id(current), false, 0, 10);
        assertThat(result.total()).isEqualTo(5);
        assertThat(result.counts()).isEqualTo(new InventoryComparison.Counts(1, 1, 2, 1));
        assertThat(result.catalogue()).isEqualTo(new InventoryComparison.Catalogue(1, 1, 0, 1, 1));
        assertThat(result.rows())
                .anySatisfy(row -> {
                    assertThat(row.productId()).isEqualTo(UUID.fromString(added));
                    assertThat(row.previousQuantity()).isNull();
                    assertThat(row.currentQuantity()).isEqualTo("0.000");
                    assertThat(row.delta()).isNull();
                })
                .anySatisfy(row -> {
                    assertThat(row.productId()).isEqualTo(UUID.fromString(removed));
                    assertThat(row.currentQuantity()).isNull();
                    assertThat(row.percent()).isNull();
                })
                .anySatisfy(row -> {
                    assertThat(row.productId()).isEqualTo(UUID.fromString(zero));
                    assertThat(row.delta()).isEqualTo("2.003");
                    assertThat(row.percent()).isNull();
                })
                .anySatisfy(row -> assertThat(row.delta()).isEqualTo("0.001"));
        var outside = compare(a, id(prior), id(current), true, 20, 1);
        assertThat(outside.total()).isEqualTo(4);
        assertThat(outside.rows()).isEmpty();
        assertThatThrownBy(() -> compare(a, id(current), id(prior), false, 0, 1))
                .hasMessageContaining("400");
        assertThatThrownBy(() -> compare(b, id(prior), id(current), false, 0, 1))
                .hasMessageContaining("404");
        a.install.revoke(Instant.now(), "test", "retired");
        installations.saveAndFlush(a.install);
        var relinked =
                installation(a.install.getCompany(), a.install.getStore(), a.install.getLicense());
        var afterRelink = empty(relinked);
        sync.receive(request(relinked, afterRelink), relinked.token);
        assertThatThrownBy(() -> compare(a, id(prior), id(afterRelink), false, 0, 10))
                .hasMessageContaining("409");
    }

    private InventoryComparison compare(
            Site a, UUID previous, UUID current, boolean changedOnly, int page, int size) {
        return comparison.compare(
                a.company,
                a.store,
                previous,
                current,
                null,
                null,
                changedOnly,
                InventoryComparison.Sort.PRODUCT,
                InventoryComparison.Direction.ASC,
                page,
                size);
    }

    @Test
    void movementReportsUseImmutableIdentitiesUtcOccurrenceAndExactQuantities() throws Exception {
        var a = site();
        var b = site();
        var capture = fixture(a);
        sync.receive(request(a, capture), a.token);
        jdbc.update(
                "update saas_store set"
                    + " store_address='{\"pais\":\"ES\",\"codigoPostal\":\"35001\"}'::jsonb where"
                    + " id=?",
                a.store);
        UUID product = UUID.fromString("beb8b102-9c02-4796-95c4-cf0d01e066c8");
        UUID warehouse = UUID.fromString("12b9dc65-f43e-49fe-9c1e-3fcba4f57db9");
        var good = new HashMap<String, Object>();
        good.putAll(
                Map.of(
                        "productoId",
                        product,
                        "almacenId",
                        warehouse,
                        "tipo",
                        "AJUSTE",
                        "cantidad",
                        "-9999999999999999.125",
                        "creadoEn",
                        "2026-01-01T23:59:59.999999999Z",
                        "motivo",
                        "PRIVATE",
                        "usuarioId",
                        UUID.randomUUID()));
        UUID original = UUID.randomUUID(), conflict = UUID.randomUUID();
        sendMovement(a, original, good);
        sendMovement(a, original, good);
        sendMovement(b, original, good);
        sendMovement(a, conflict, good);
        var altered = new HashMap<>(good);
        altered.put("creadoEn", "2026-12-15T00:00:00Z");
        sendMovement(a, conflict, altered);
        var older = new HashMap<>(good);
        older.put("cantidad", new java.math.BigDecimal("1.375"));
        older.put("creadoEn", "2025-12-31T12:00:01Z");
        sendMovement(a, UUID.randomUUID(), older);
        altered.put("creadoEn", "2026-02-30T12:00:00Z");
        sendMovement(a, UUID.randomUUID(), altered);
        altered.put("creadoEn", "2026-01-01T00:00:00Z");
        altered.put("cantidad", "0.0004");
        sendMovement(a, UUID.randomUUID(), altered);
        altered.put("cantidad", 3);
        altered.put("tipo", "TYPO");
        sendMovement(a, UUID.randomUUID(), altered);
        for (String kind : List.of("TRANSFERENCIA_SALIDA", "SALIDA_ALMACEN", "TRANSFERENCIA_ENTRADA", "ENTRADA_ALMACEN")) {
            var wrongSign = new HashMap<>(good);
            wrongSign.put("tipo", kind);
            wrongSign.put("cantidad", kind.endsWith("SALIDA") || kind.equals("SALIDA_ALMACEN") ? "3.000" : "-3.000");
            sendMovement(a, UUID.randomUUID(), wrongSign);
        }
        var filter =
                new MovementReport.Filter(
                        a.company, a.store, "35", warehouse, product, MovementReport.Type.AJUSTE);
        var date = java.time.LocalDate.parse("2026-01-01");
        var period = Period.inclusive(date, date);
        var previous = Period.inclusive(date.minusDays(1), date.minusDays(1));
        var longer = Period.inclusive(date.minusDays(2), date.minusDays(1));
        var unequal = movements.summary(filter, period, longer, MovementReport.GroupBy.DAY, 0, 5);
        assertThat(unequal.rows()).hasSize(2);
        assertThat(unequal.rows().get(1).label()).isNull();
        assertThat(unequal.rows().get(1).currentCount()).isNull();
        assertThat(unequal.rows().get(0).comparisonCount()).isZero();
        var reversed = movements.summary(filter, longer, period, MovementReport.GroupBy.DAY, 0, 5);
        assertThat(reversed.rows().get(1).comparisonCount()).isNull();
        for (var group : MovementReport.GroupBy.values()) {
            var result = movements.summary(filter, period, previous, group, 0, 5);
            assertThat(result.coverage()).isEqualTo(new MovementReport.Coverage(7, 1, 1));
            assertThat(result.currentCount()).isOne();
            assertThat(result.comparisonCount()).isOne();
            assertThat(result.rows()).hasSize(1);
        }
        var ledger =
                movements.ledger(
                        filter,
                        period,
                        MovementReport.Sort.PRODUCT,
                        MovementReport.Direction.DESC,
                        0,
                        10);
        assertThat(ledger.rows())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.quantity()).isEqualTo("-9999999999999999.125");
                    assertThat(row.occurredAt())
                            .isEqualTo(Instant.parse("2026-01-01T23:59:59.999999Z"));
                    assertThat(row.receivedAt()).isAfter(row.occurredAt());
                    assertThat(row.warehouseName()).isEqualTo("Principal");
                    assertThat(row.movementId()).isEqualTo(original);
                });
        assertThat(mapper.writeValueAsString(ledger))
                .doesNotContain("PRIVATE", "usuarioId", "motivo");
        var emptyPage =
                movements.ledger(
                        filter,
                        period,
                        MovementReport.Sort.TYPE,
                        MovementReport.Direction.DESC,
                        5,
                        1);
        assertThat(emptyPage.total()).isOne();
        assertThat(emptyPage.rows()).isEmpty();
        assertThat(
                        movements
                                .summary(
                                        new MovementReport.Filter(
                                                a.company, a.store, "28", null, null, null),
                                        period,
                                        null,
                                        MovementReport.GroupBy.TYPE,
                                        0,
                                        1)
                                .currentCount())
                .isZero();
        assertThatThrownBy(() ->
                        movements.summary(
                                new MovementReport.Filter(
                                        b.company, a.store, null, null, null, null),
                                period,
                                null,
                                MovementReport.GroupBy.DAY,
                                0,
                                10))
                .hasMessageContaining("400");
        assertThat(reports.provinces(a.company, a.store, null))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.province()).isEqualTo("35");
                    assertThat(p.totals().reportedStores()).isOne();
                });
    }

    private void sendMovement(Site a, UUID id, Map<String, Object> body) {
        sync.receive(
                new SyncEventRequest(
                        UUID.randomUUID(),
                        a.company,
                        a.store,
                        null,
                        "STOCK_MOVEMENT",
                        id,
                        SyncOperation.CREAR,
                        body),
                a.token);
    }

    @Test
    void allStaffCanReadComparisonsMovementsAndProvinceCoverage()
            throws Exception {
        var a = site();
        var first = empty(a);
        first.put("capturedAt", "2025-01-01T12:00:00Z");
        var second = empty(a);
        sync.receive(request(a, first), a.token);
        sync.receive(request(a, second), a.token);
        String ids = "?companyId=" + a.company + "&storeId=" + a.store;
        String dates = ids + "&from=2026-01-01&to=2026-01-01";
        String read = admin("VIEW_ADMIN_DATA"), report = admin("VIEW_REPORTS");
        for (String path :
                List.of(
                        "inventory/comparison"
                                + ids
                                + "&previousSnapshotId="
                                + id(first)
                                + "&currentSnapshotId="
                                + id(second),
                        "inventory/provinces" + ids,
                        "movements" + dates,
                        "movements/ledger" + dates)) {
            String url = "/api/v1/admin/reports/" + path;
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).header("Authorization", read)).andExpect(status().isOk());
            mvc.perform(get(url).header("Authorization", report))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    private String admin(String permission) {
        String name = "inv-" + UUID.randomUUID();
        var account =
                admins.saveAndFlush(
                        new SaasAdminUser(UUID.randomUUID(), name, "unused", true, Instant.now()));
        var role = UUID.randomUUID();
        jdbc.update(
                "insert into saas_admin_role(id,name,created_at) values(?,?,now())", role, name);
        jdbc.update(
                "insert into saas_admin_user_role(user_id,role_id) values(?,?)",
                account.getId(),
                role);
        jdbc.update(
                "insert into saas_admin_role_permission(role_id,permission_code) values(?,?)",
                role,
                permission);
        return "Bearer " + sessions.issue("admin", name).token();
    }

    private Map<String, Object> fixture(Site a) throws Exception {
        var f =
                mapper.readValue(
                        Path.of("..", "contracts", "sync", "inventory-snapshot-v1.json").toFile(),
                        new TypeReference<Map<String, Object>>() {});
        f.put("sourceInstallationId", a.install.getInstallationId().toString());
        return f;
    }

    private Map<String, Object> empty(Site a) throws Exception {
        var f = fixture(a);
        f.put("snapshotId", UUID.randomUUID().toString());
        for (var key : List.of("products", "warehouses", "balances")) f.put(key, List.of());
        for (var key : List.of("productCount", "warehouseCount", "balanceCount")) f.put(key, 0);
        return f;
    }

    private void rejected(Site a, Map<String, Object> data, String status) {
        var req = request(a, data);
        assertThatThrownBy(() -> sync.receive(req, a.token))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(status);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from saas_sync_event where event_id=?",
                                Long.class,
                                req.eventId()))
                .isZero();
    }

    private UUID id(Map<String, Object> f) {
        return UUID.fromString(f.get("snapshotId").toString());
    }

    private SyncEventRequest request(Site a, Map<String, Object> f) {
        return new SyncEventRequest(
                UUID.randomUUID(),
                a.company(),
                a.store(),
                null,
                InventorySnapshotProjector.ENTITY_TYPE,
                id(f),
                SyncOperation.ACTUALIZAR,
                f);
    }

    private Site site() {
        Instant now = Instant.now();
        var company =
                companies.saveAndFlush(
                        new SaasCompany(
                                UUID.randomUUID(),
                                "Inventory " + NUM.incrementAndGet(),
                                validCif("B" + NUM.get() + "0"),
                                TaxpayerType.SOCIEDAD,
                                TaxRegime.IVA,
                                now));
        var store =
                stores.saveAndFlush(
                        new SaasStore(UUID.randomUUID(), company, "001", "Principal", "UTC", now));
        var license =
                licenses.saveAndFlush(
                        new SaasLicense(
                                UUID.randomUUID(),
                                company,
                                "INV-" + UUID.randomUUID(),
                                now.plusSeconds(86400),
                                1,
                                1,
                                now));
        return installation(company, store, license);
    }

    private Site installation(SaasCompany company, SaasStore store, SaasLicense license) {
        String token = tokens.newToken();
        var instance =
                installations.saveAndFlush(
                        new SaasInstallation(
                                UUID.randomUUID(),
                                company,
                                store,
                                license,
                                UUID.randomUUID(),
                                "Inventory",
                                null,
                                tokens.hash(token),
                                Instant.now()));
        return new Site(company.getId(), store.getId(), instance, token);
    }

    record Site(UUID company, UUID store, SaasInstallation install, String token) {}
}

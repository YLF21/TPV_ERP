package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.LicenseSaasCacheAuthenticator;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, InventorySnapshotCapture.class, SyncOutboxService.class,
        InventorySnapshotPostgreSqlTest.ClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "TPV_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_TEST_DB_PASSWORD", matches = ".+")
class InventorySnapshotPostgreSqlTest {
    private static final String URL = System.getenv("TPV_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_TEST_DB_USERNAME");
    private static final String PASSWORD = System.getenv("TPV_TEST_DB_PASSWORD");
    private static final String SCHEMA = "inventory_snapshot_" + UUID.randomUUID().toString().replace("-", "");
    private static final String ADDRESS = """
            {"linea1":"Test","ciudad":"Test","codigoPostal":"1","provincia":"Test","pais":"ES"}
            """;
    private static final Duration CADENCE = Duration.ofHours(1);

    @TestConfiguration
    static class ClockConfig {
        @Bean Clock inventorySnapshotTestClock() { return Clock.systemUTC(); }
    }

    @Autowired InventorySnapshotCapture capture;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean LicenseRepository licenses;
    @MockitoBean LicenseSaasCacheAuthenticator authenticator;
    @MockitoSpyBean SyncOutboxService outbox;
    private final ObjectMapper mapper = new ObjectMapper();
    private UUID installationId;
    private InventorySnapshotCapture.Site site;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void setup() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);
        jdbc.update("""
                insert into instalacion(id,referencia,public_key,creada_en,demo_hasta)
                values (?,'TEST-SNAPSHOT','test','2026-01-01T12:00:00Z','2026-01-31T12:00:00Z')
                on conflict (singleton_key) do nothing
                """, UUID.randomUUID());
        installationId = jdbc.queryForObject("select id from instalacion", UUID.class);
        site = store();
        authorize(site);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (!SCHEMA.matches("inventory_snapshot_[0-9a-f]{32}")) throw new IllegalStateException(SCHEMA);
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @Test
    void emptyOwnedStoreEnqueuesACompleteSnapshotAndHonorsCadenceAndRetries() throws Exception {
        assertThat(capture.sites()).containsExactly(site);
        assertThat(capture.enqueue(site, CADENCE)).isOne();
        var payload = payloads().getFirst();
        assertThat(payload.path("products").isEmpty()).isTrue();
        assertThat(payload.path("warehouses").isEmpty()).isTrue();
        assertThat(payload.path("balances").isEmpty()).isTrue();
        assertThat(payload.path("chunkIndex").asInt()).isZero();
        assertThat(payload.path("chunkCount").asInt()).isOne();
        assertThat(payload.path("capturedAt").asText()).matches(".+\\.\\d{6}Z");
        assertThat(Instant.parse(payload.path("capturedAt").asText())).isEqualTo(jdbc.queryForObject(
                "select captured_at from inventory_snapshot_sync_checkpoint where store_id = ?",
                java.time.OffsetDateTime.class, site.storeId()).toInstant());

        setStatus("ERROR");
        ageCheckpoint();
        assertThat(capture.enqueue(site, CADENCE)).isZero();
        setStatus("DEAD_LETTER");
        assertThat(capture.enqueue(site, CADENCE)).isOne();
        setStatus("ENVIADO");
        assertThat(capture.enqueue(site, CADENCE)).isZero();
        assertThat(payloads()).hasSize(2);
    }

    @Test
    void capturesAcrossChunksWithStoreScopeAndPreservesCatalogAndStockRows() throws Exception {
        var foreign = store();
        UUID product = products(site, 500);
        UUID productWithBlankIdentifier = jdbc.queryForObject(
                "select id from producto where tienda_id = ? and id <> ? order by id limit 1",
                UUID.class, site.storeId(), product);
        UUID foreignProduct = products(foreign, 1);
        UUID warehouse = warehouse(site, "Almacén inactivo", false, true);
        UUID foreignWarehouse = warehouse(foreign, "Almacén ajeno", true, false);
        jdbc.update("update producto set activo = false, product_type = 'WEIGHT' where id = ?", product);
        identifier(product, site, "CODIGO", "P001");
        identifier(product, site, "CODIGO_BARRAS", "000123");
        identifier(product, site, "CODIGO_BARRAS_2", "000456");
        identifier(productWithBlankIdentifier, site, "CODIGO_BARRAS", "\t");
        stock(product, warehouse, "-9999999999999999.125");
        stock(product, foreignWarehouse, "47.700");
        stock(foreignProduct, warehouse, "83.900");
        var stockBefore = jdbc.queryForList("select * from existencia order by id");
        var catalogBefore = jdbc.queryForList("select * from producto order by id");

        assertThat(capture.enqueue(site, CADENCE)).isEqualTo(2);
        var chunks = payloads();
        assertThat(chunks).hasSize(2);
        var first = chunks.get(0);
        var second = chunks.get(1);
        assertThat(first.path("products").size()).isEqualTo(500);
        assertThat(first.toString()).doesNotContain(foreignProduct.toString());
        assertThat(second.toString()).doesNotContain(foreignWarehouse.toString());
        assertThat(first.path("snapshotId")).isEqualTo(second.path("snapshotId"));
        assertThat(first.path("capturedAt")).isEqualTo(second.path("capturedAt"));
        assertThat(first.path("productCount").asInt()).isEqualTo(500);
        assertThat(first.path("warehouseCount").asInt()).isOne();
        assertThat(first.path("balanceCount").asInt()).isOne();
        assertThat(first.path("sourceInstallationId").asText()).isEqualTo(site.installationId().toString());
        assertThat(first.path("chunkIndex").asInt()).isZero();
        assertThat(second.path("chunkIndex").asInt()).isOne();
        assertThat(second.path("chunkCount").asInt()).isEqualTo(2);
        var projectedProduct = first.path("products").valueStream()
                .filter(node -> product.toString().equals(node.path("productId").asText())).findFirst().orElseThrow();
        assertThat(projectedProduct.path("code").asText()).isEqualTo("P001");
        assertThat(projectedProduct.path("barcode").asText()).isEqualTo("000123");
        assertThat(projectedProduct.path("secondaryBarcode").asText()).isEqualTo("000456");
        assertThat(projectedProduct.path("active").asBoolean()).isFalse();
        assertThat(projectedProduct.path("productType").asText()).isEqualTo("WEIGHT");
        assertThat(projectedProduct.path("familyName").asText()).isEqualTo("Familia");
        assertThat(projectedProduct.path("subfamilyId").isNull()).isTrue();
        var noBarcode = first.path("products").valueStream()
                .filter(node -> productWithBlankIdentifier.toString().equals(node.path("productId").asText()))
                .findFirst().orElseThrow();
        assertThat(noBarcode.path("barcode").isNull()).isTrue();
        assertThat(second.path("warehouses").get(0).path("defaultWarehouse").asBoolean()).isTrue();
        assertThat(second.path("balances").get(0).path("quantity").textValue()).isEqualTo("-9999999999999999.125");
        var contract = mapper.readTree(Path.of("..", "contracts", "sync", "inventory-snapshot-v1.json").toFile());
        assertSameFields(first, contract);
        assertSameFields(projectedProduct, contract.path("products").get(0));
        assertSameFields(second.path("warehouses").get(0), contract.path("warehouses").get(0));
        assertSameFields(second.path("balances").get(0), contract.path("balances").get(0));
        assertThat(jdbc.queryForList("select * from existencia order by id")).isEqualTo(stockBefore);
        assertThat(jdbc.queryForList("select * from producto order by id")).isEqualTo(catalogBefore);
    }

    @Test
    void readsBalancesAtTheSameCutoffWhenStockChangesBetweenChunks() throws Exception {
        UUID product = products(site, 500);
        UUID warehouse = warehouse(site, "Principal", true, true);
        stock(product, warehouse, "3.125");
        try (var executor = Executors.newSingleThreadExecutor()) {
            SyncOutboxService target = AopTestUtils.getUltimateTargetObject(outbox);
            doAnswer(invocation -> {
                SyncOutboundEventCommand chunk = invocation.getArgument(0);
                if (chunk.payload().get("chunkIndex").equals(0)) {
                    executor.submit(() -> jdbc.update("update existencia set cantidad = 8.750 where producto_id = ?",
                            product)).get(5, TimeUnit.SECONDS);
                }
                return invocation.callRealMethod();
            }).when(target).enqueue(any());

            assertThat(capture.enqueue(site, CADENCE)).isEqualTo(2);
        }
        assertThat(payloads().get(1).path("balances").get(0).path("quantity").textValue()).isEqualTo("3.125");
        assertThat(jdbc.queryForObject("select cantidad from existencia where producto_id = ?",
                BigDecimal.class, product)).isEqualByComparingTo("8.750");
    }

    @Test
    void rollsBackAllChunksAndCheckpointWhenAChunkFails() {
        products(site, 501);
        jdbc.execute("""
                alter table sync_outbox add constraint fail_snapshot_chunk_for_test
                check (tienda_id <> '%s' or (payload ->> 'chunkIndex')::int <> 1)
                """.formatted(site.storeId()));
        try {
            assertThatThrownBy(() -> capture.enqueue(site, CADENCE)).isInstanceOf(RuntimeException.class)
                    .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                    .hasStackTraceContaining("fail_snapshot_chunk_for_test");
            assertThat(jdbc.queryForObject("select count(*) from sync_outbox where tienda_id = ?",
                    Long.class, site.storeId())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from inventory_snapshot_sync_checkpoint where store_id = ?",
                    Long.class, site.storeId())).isZero();
        } finally {
            jdbc.execute("alter table sync_outbox drop constraint fail_snapshot_chunk_for_test");
        }
        assertThat(capture.enqueue(site, CADENCE)).isEqualTo(2);
    }

    @Test
    void skipsACompetingCaptureAndRejectsAnUnownedStore() {
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                        Boolean.class, "inventory-snapshot:" + site.storeId())).isTrue();
                var result = executor.submit(() -> capture.enqueue(site, CADENCE));
                assertThat(result).succeedsWithin(5, TimeUnit.SECONDS).isEqualTo(0);
            });
        }
        assertThat(capture.enqueue(store(), CADENCE)).isZero();
        assertThat(capture.enqueue(site, CADENCE)).isOne();
    }

    private InventorySnapshotCapture.Site store() {
        UUID company = UUID.randomUUID(), store = UUID.randomUUID();
        jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,?,'Test',cast(? as jsonb))",
                company, company.toString(), ADDRESS);
        jdbc.update("""
                insert into tienda(id,empresa_id,codigo_tienda,nombre,direccion,address_normalized_hash,timezone,moneda,locale)
                values (?,?,'001','Test',cast(? as jsonb),?,'UTC','EUR','es-ES')
                """, store, company, ADDRESS, store.toString());
        return new InventorySnapshotCapture.Site(company, store, installationId);
    }

    private void authorize(InventorySnapshotCapture.Site owned) {
        var license = mock(License.class);
        when(license.getLocalCompanyId()).thenReturn(owned.companyId());
        when(license.getTiendaId()).thenReturn(owned.storeId());
        when(license.getInstalacionId()).thenReturn(owned.installationId());
        when(license.isSaasLinked()).thenReturn(true);
        when(license.getSaasCompanyId()).thenReturn(UUID.randomUUID());
        when(license.getSaasStoreId()).thenReturn(UUID.randomUUID());
        when(authenticator.isAuthentic(license)).thenReturn(true);
        when(licenses.findByActivaTrueOrderByValidaDesdeDesc()).thenReturn(List.of(license));
        when(licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(owned.storeId(), owned.installationId()))
                .thenReturn(Optional.of(license));
    }

    private UUID products(InventorySnapshotCapture.Site owner, int count) {
        UUID family = UUID.randomUUID(), tax = UUID.randomUUID();
        jdbc.update("insert into familia(id,tienda_id,nombre,family_id,family_code) values (?,?,'Familia','FAMILIA','001')",
                family, owner.storeId());
        jdbc.update("insert into impuesto_tienda(id,tienda_id,porcentaje) values (?,?,0)", tax, owner.storeId());
        return jdbc.queryForList("""
                insert into producto(id,tienda_id,familia_id,impuesto_id,nombre)
                select gen_random_uuid(), ?, ?, ?, 'Producto ' || n from generate_series(1, ?) n returning id
                """, UUID.class, owner.storeId(), family, tax, count).getFirst();
    }

    private UUID warehouse(InventorySnapshotCapture.Site owner, String name, boolean active, boolean defaultWarehouse) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into almacen(id,tienda_id,nombre,activo,predeterminado) values (?,?,?,?,?)",
                id, owner.storeId(), name, active, defaultWarehouse);
        return id;
    }

    private void stock(UUID product, UUID warehouse, String quantity) {
        jdbc.update("insert into existencia(id,producto_id,almacen_id,cantidad,version) values (?,?,?,?,9)",
                UUID.randomUUID(), product, warehouse, new BigDecimal(quantity));
    }

    private void identifier(UUID product, InventorySnapshotCapture.Site owner, String code, String value) {
        jdbc.update("insert into producto_identificador(id,tienda_id,producto_id,tipo,valor) values (?,?,?,?,?)",
                UUID.randomUUID(), owner.storeId(), product, code, value);
    }

    private void setStatus(String status) {
        jdbc.update("update sync_outbox set estado = ? where tienda_id = ?", status, site.storeId());
    }

    private void ageCheckpoint() {
        jdbc.update("update inventory_snapshot_sync_checkpoint set captured_at = now() - interval '2 hours' where store_id = ?",
                site.storeId());
    }

    private List<JsonNode> payloads() throws Exception {
        var rows = jdbc.queryForList("""
                select payload::text from sync_outbox where tienda_id = ?
                order by payload ->> 'capturedAt', (payload ->> 'chunkIndex')::int
                """, String.class, site.storeId());
        var payloads = new java.util.ArrayList<JsonNode>();
        for (var row : rows) payloads.add(mapper.readTree(row));
        return payloads;
    }

    private static void assertSameFields(JsonNode actual, JsonNode contract) {
        assertThat(actual.properties().stream().map(java.util.Map.Entry::getKey))
                .containsExactlyInAnyOrderElementsOf(contract.properties().stream().map(java.util.Map.Entry::getKey).toList());
    }
}

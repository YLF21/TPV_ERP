package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.catalog.ProductSupplierRepository;
import com.tpverp.backend.party.Supplier;
import com.tpverp.backend.party.SupplierRepository;
import jakarta.persistence.EntityManagerFactory;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class ProductSupplierRepositoryPostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA =
            "tpv_erp_repository_" + UUID.randomUUID().toString().replace("-", "");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private SupplierRepository suppliers;
    @Autowired private ProductSupplierRepository productSuppliers;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL
                + (URL.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    @AfterAll
    static void dropSchema() {
        execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    void repositoriesOrderBySupplierDocumentWithoutDuplicatesOrLazyRepresentativeLoads() {
        TestIds ids = insertFixture();

        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var orderedSuppliers = suppliers.findByCompanyIdOrderByDocumentNumberAsc(ids.companyId());
        orderedSuppliers.forEach(value -> value.getRepresentatives().size());

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        statistics.clear();
        var orderedLinks = productSuppliers.findForProduct(ids.productId(), ids.storeId());

        assertThat(orderedSuppliers)
                .extracting(Supplier::getDocumentNumber)
                .containsExactly("A00000001", "B00000001");
        assertThat(orderedSuppliers).doesNotHaveDuplicates();
        assertThat(orderedSuppliers.getFirst().getRepresentatives()).hasSize(2);
        assertThat(orderedLinks)
                .extracting(link -> link.getSupplier().getDocumentNumber())
                .containsExactly("A00000001", "B00000001");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void purchaseUpsertIsAtomicAndPreservesReferenceWithNonDecreasingDate() {
        TestIds ids = insertFixture();
        jdbc.update("""
                delete from %s.producto_proveedor
                where producto_id = ? and proveedor_id = ?
                """.formatted(SCHEMA), ids.productId(), ids.firstSupplierId());

        productSuppliers.upsertPurchase(
                UUID.randomUUID(), ids.productId(), ids.firstSupplierId(),
                LocalDate.of(2026, 6, 9));
        assertLink(ids, null, LocalDate.of(2026, 6, 9), 0L);

        jdbc.update("""
                update %s.producto_proveedor
                set referencia_proveedor = 'REF-EXISTENTE'
                where producto_id = ? and proveedor_id = ?
                """.formatted(SCHEMA), ids.productId(), ids.firstSupplierId());

        productSuppliers.upsertPurchase(
                UUID.randomUUID(), ids.productId(), ids.firstSupplierId(),
                LocalDate.of(2026, 5, 1));
        assertLink(ids, "REF-EXISTENTE", LocalDate.of(2026, 6, 9), 1L);

        productSuppliers.upsertPurchase(
                UUID.randomUUID(), ids.productId(), ids.firstSupplierId(),
                LocalDate.of(2026, 7, 1));
        assertLink(ids, "REF-EXISTENTE", LocalDate.of(2026, 7, 1), 2L);
    }

    @Test
    void concurrentPurchaseUpsertsProduceOneRowWithGreatestDate() throws Exception {
        TestIds ids = insertFixture();
        jdbc.update("""
                delete from %s.producto_proveedor
                where producto_id = ? and proveedor_id = ?
                """.formatted(SCHEMA), ids.productId(), ids.firstSupplierId());
        org.springframework.test.context.transaction.TestTransaction.flagForCommit();
        org.springframework.test.context.transaction.TestTransaction.end();

        var firstWritten = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> concurrentUpsert(
                    ids, LocalDate.of(2026, 6, 9), firstWritten, releaseFirst));
            assertThat(firstWritten.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> concurrentUpsert(
                    ids, LocalDate.of(2026, 7, 1), null, null));

            Thread.sleep(200);
            assertThat(second.isDone()).isFalse();
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }

        assertLink(ids, null, LocalDate.of(2026, 7, 1), 1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from %s.producto_proveedor
                where producto_id = ? and proveedor_id = ?
                """.formatted(SCHEMA), Integer.class,
                ids.productId(), ids.firstSupplierId())).isEqualTo(1);
    }

    private void concurrentUpsert(
            TestIds ids,
            LocalDate date,
            CountDownLatch written,
            CountDownLatch release) {
        String connectionUrl = URL + (URL.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA;
        try (var connection = DriverManager.getConnection(connectionUrl, USER, PASSWORD);
                var statement = connection.prepareStatement("""
                        insert into producto_proveedor as current_link (
                            id, producto_id, proveedor_id, referencia_proveedor,
                            ultima_fecha_entrada, version)
                        values (?, ?, ?, null, ?, 0)
                        on conflict (producto_id, proveedor_id) do update
                        set ultima_fecha_entrada = greatest(
                                current_link.ultima_fecha_entrada,
                                excluded.ultima_fecha_entrada),
                            version = current_link.version + 1
                        """)) {
            connection.setAutoCommit(false);
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, ids.productId());
            statement.setObject(3, ids.firstSupplierId());
            statement.setObject(4, date);
            statement.executeUpdate();
            if (written != null) {
                written.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("No se libero la primera transaccion");
                }
            }
            connection.commit();
        } catch (Exception exception) {
            throw new IllegalStateException("Fallo el UPSERT concurrente", exception);
        }
    }

    private void assertLink(
            TestIds ids, String reference, LocalDate lastEntryDate, long version) {
        Map<String, Object> row = jdbc.queryForMap("""
                select referencia_proveedor, ultima_fecha_entrada, version
                from %s.producto_proveedor
                where producto_id = ? and proveedor_id = ?
                """.formatted(SCHEMA), ids.productId(), ids.firstSupplierId());
        assertThat(row.get("referencia_proveedor")).isEqualTo(reference);
        assertThat(((java.sql.Date) row.get("ultima_fecha_entrada")).toLocalDate())
                .isEqualTo(lastEntryDate);
        assertThat(row.get("version")).isEqualTo(version);
    }

    private TestIds insertFixture() {
        var ids = new TestIds(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("""
                insert into %s.empresa (id, tax_id, razon_social, domicilio_fiscal)
                values (?, 'B00000000', 'Company', cast(? as jsonb))
                """.formatted(SCHEMA), ids.companyId(), address());
        jdbc.update("""
                insert into %s.tienda (
                    id, empresa_id, codigo_tienda, nombre, direccion, address_normalized_hash,
                    timezone, moneda, locale)
                values (?, ?, '001', 'Store', cast(? as jsonb), 'hash',
                    'Atlantic/Canary', 'EUR', 'es-ES')
                """.formatted(SCHEMA), ids.storeId(), ids.companyId(), address());
        jdbc.update("""
                insert into %s.impuesto_tienda (id, tienda_id, porcentaje)
                values (?, ?, 21)
                """.formatted(SCHEMA), ids.taxId(), ids.storeId());
        jdbc.update("""
                insert into %s.familia (id, tienda_id, nombre)
                values (?, ?, 'GENERAL')
                """.formatted(SCHEMA), ids.familyId(), ids.storeId());
        jdbc.update("""
                insert into %s.producto (
                    id, tienda_id, familia_id, impuesto_id, nombre)
                values (?, ?, ?, ?, 'Producto')
                """.formatted(SCHEMA), ids.productId(), ids.storeId(),
                ids.familyId(), ids.taxId());
        insertSupplier(ids.secondSupplierId(), ids.companyId(), "B00000001", "Segundo");
        insertSupplier(ids.firstSupplierId(), ids.companyId(), "A00000001", "Primero");
        insertRepresentative(ids.firstRepresentativeId(), ids.companyId(), "Comercial 1");
        insertRepresentative(ids.secondRepresentativeId(), ids.companyId(), "Comercial 2");
        jdbc.update("""
                insert into %s.proveedor_comercial (
                    proveedor_id, comercial_id, principal)
                values (?, ?, true), (?, ?, false)
                """.formatted(SCHEMA),
                ids.firstSupplierId(), ids.firstRepresentativeId(),
                ids.firstSupplierId(), ids.secondRepresentativeId());
        insertProductSupplier(ids.productId(), ids.secondSupplierId());
        insertProductSupplier(ids.productId(), ids.firstSupplierId());
        return ids;
    }

    private void insertSupplier(
            UUID supplierId, UUID companyId, String documentNumber, String legalName) {
        jdbc.update("""
                insert into %s.proveedor (
                    id, empresa_id, razon_social, tipo_documento, numero_documento,
                    code_supplier)
                values (?, ?, ?, 'CIF', ?, ?)
                """.formatted(SCHEMA), supplierId, companyId, legalName, documentNumber,
                documentNumber.startsWith("A") ? "S-000001" : "S-000002");
    }

    private void insertRepresentative(UUID id, UUID companyId, String name) {
        jdbc.update("""
                insert into %s.comercial (id, empresa_id, nombre, code_commercial)
                values (?, ?, ?, ?)
                """.formatted(SCHEMA), id, companyId, name,
                name.endsWith("1") ? "CO-000001" : "CO-000002");
    }

    private void insertProductSupplier(UUID productId, UUID supplierId) {
        jdbc.update("""
                insert into %s.producto_proveedor (id, producto_id, proveedor_id)
                values (?, ?, ?)
                """.formatted(SCHEMA), UUID.randomUUID(), productId, supplierId);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " no configurada");
        }
        return value;
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("No se pudo preparar PostgreSQL", exception);
        }
    }

    private static String address() {
        return """
                {
                    "linea1":"Calle Uno",
                    "ciudad":"Las Palmas",
                    "codigoPostal":"35001",
                    "provincia":"Las Palmas",
                    "pais":"ES"
                }
                """;
    }

    private record TestIds(
            UUID companyId,
            UUID storeId,
            UUID taxId,
            UUID familyId,
            UUID productId,
            UUID firstSupplierId,
            UUID secondSupplierId,
            UUID firstRepresentativeId,
            UUID secondRepresentativeId) {
    }
}

package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("test")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProductImportMetadataTest {

    private static final String URL = environment("TPV_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
    private static final String USER = environment("TPV_TEST_DB_USERNAME", "tpv_erp_test");
    private static final String PASSWORD = environment("TPV_TEST_DB_PASSWORD", "admin");
    private static final String SCHEMA =
            "tpv_erp_import_metadata_" + UUID.randomUUID().toString().replace("-", "");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProductImportLineMetadataRepository repository;

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
    }

    @AfterAll
    static void dropSchema() {
        execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    void lineMetadataNormalizesSupplierReference() {
        var documentId = UUID.randomUUID();
        var productId = UUID.randomUUID();

        var metadata = new ProductImportLineMetadata(documentId, productId, " ref-1 ");

        assertThat(metadata.documentId()).isEqualTo(documentId);
        assertThat(metadata.productId()).isEqualTo(productId);
        assertThat(metadata.supplierReference()).isEqualTo("REF-1");
    }

    @Test
    void blankSupplierReferenceIsStoredAsNull() {
        var metadata = new ProductImportLineMetadata(UUID.randomUUID(), UUID.randomUUID(), "   ");

        assertThat(metadata.supplierReference()).isNull();
    }

    @Test
    void lineMetadataMapsToImportTable() {
        assertThat(ProductImportLineMetadata.class.isAnnotationPresent(Entity.class)).isTrue();
        assertThat(ProductImportLineMetadata.class.getAnnotation(Table.class).name())
                .isEqualTo("producto_importacion_excel_linea");
    }

    @Test
    void repositoryFindsAndDeletesMetadataByDocumentId() {
        var ids = insertFixture();
        repository.saveAll(List.of(
                new ProductImportLineMetadata(
                        ids.documentAId(), ids.productAId(), "REF-A1"),
                new ProductImportLineMetadata(
                        ids.documentAId(), ids.productBId(), "REF-A2"),
                new ProductImportLineMetadata(
                        ids.documentBId(), ids.productAId(), "REF-B1")));
        repository.flush();

        assertThat(repository.findByDocumentId(ids.documentAId()))
                .extracting(ProductImportLineMetadata::documentId,
                        ProductImportLineMetadata::productId,
                        ProductImportLineMetadata::supplierReference)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                ids.documentAId(), ids.productAId(), "REF-A1"),
                        org.assertj.core.groups.Tuple.tuple(
                                ids.documentAId(), ids.productBId(), "REF-A2"));

        repository.deleteByDocumentId(ids.documentAId());
        repository.flush();

        assertThat(repository.findByDocumentId(ids.documentAId())).isEmpty();
        assertThat(repository.findByDocumentId(ids.documentBId()))
                .extracting(ProductImportLineMetadata::documentId,
                        ProductImportLineMetadata::productId,
                        ProductImportLineMetadata::supplierReference)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        ids.documentBId(), ids.productAId(), "REF-B1"));
    }

    private Fixture insertFixture() {
        var ids = new Fixture(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
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
                insert into %s.rol (id, tienda_id, nombre, protegido)
                values (?, ?, 'ADMIN', true)
                """.formatted(SCHEMA), ids.roleId(), ids.storeId());
        jdbc.update("""
                insert into %s.usuario (
                    id, tienda_id, nombre, user_name, password_hash, rol_id, protegido)
                values (?, ?, 'ADMIN', 'ADMIN', 'hash', ?, true)
                """.formatted(SCHEMA), ids.userId(), ids.storeId(), ids.roleId());
        jdbc.update("""
                insert into %s.impuesto_tienda (id, tienda_id, porcentaje)
                values (?, ?, 21)
                """.formatted(SCHEMA), ids.taxId(), ids.storeId());
        jdbc.update("""
                insert into %s.familia (id, tienda_id, nombre)
                values (?, ?, 'GENERAL')
                """.formatted(SCHEMA), ids.familyId(), ids.storeId());
        jdbc.update("""
                insert into %s.almacen (id, tienda_id, nombre, predeterminado)
                values (?, ?, 'GENERAL', true)
                """.formatted(SCHEMA), ids.warehouseId(), ids.storeId());
        insertProduct(ids.productAId(), ids);
        insertProduct(ids.productBId(), ids);
        insertDocument(ids.documentAId(), ids);
        insertDocument(ids.documentBId(), ids);
        return ids;
    }

    private void insertProduct(UUID productId, Fixture ids) {
        jdbc.update("""
                insert into %s.producto (
                    id, tienda_id, familia_id, impuesto_id, nombre)
                values (?, ?, ?, ?, 'Producto')
                """.formatted(SCHEMA), productId, ids.storeId(),
                ids.familyId(), ids.taxId());
    }

    private void insertDocument(UUID documentId, Fixture ids) {
        jdbc.update("""
                insert into %s.documento (
                    id, tienda_id, almacen_id, tipo, estado, fecha, creado_en,
                    creado_por, descuento_global, base_total, impuesto_total,
                    total, moneda)
                values (?, ?, ?, 'ALBARAN_COMPRA', 'BORRADOR', ?, ?, ?,
                    0, 0, 0, 0, 'EUR')
                """.formatted(SCHEMA),
                documentId, ids.storeId(), ids.warehouseId(),
                LocalDate.of(2026, 7, 1),
                java.sql.Timestamp.from(Instant.parse("2026-07-01T12:00:00Z")),
                ids.userId());
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("No se pudo preparar PostgreSQL", exception);
        }
    }

    private static String environment(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
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

    private record Fixture(
            UUID companyId,
            UUID storeId,
            UUID roleId,
            UUID userId,
            UUID taxId,
            UUID familyId,
            UUID warehouseId,
            UUID productAId,
            UUID productBId,
            UUID documentAId,
            UUID documentBId) {
    }
}

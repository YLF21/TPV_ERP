package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.catalog.ProductPriceHistoryRepository;
import com.tpverp.backend.catalog.ProductPriceHistoryType;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class PreviousTicketImportRepositoryPostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA =
            "previous_ticket_import_" + UUID.randomUUID().toString().replace("-", "");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private CommercialDocumentRepository documents;
    @Autowired private ProductPriceHistoryRepository priceHistory;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL
                + (URL.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA + ",public");
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
    void latestMixedExchangeIsSkippedInFavourOfPreviousNormalSale() {
        var fixture = insertFixture();
        var previousSale = UUID.randomUUID();
        var returnDocument = UUID.randomUUID();
        var latestMixedSale = UUID.randomUUID();

        insertDocument(fixture, previousSale, "T-1", "2026-08-07 10:00:00+00", "10.00");
        insertProductLine(fixture, previousSale, UUID.randomUUID(), "1.000", "10.00", null);
        insertDocument(fixture, returnDocument, "R-1", "2026-08-07 10:01:00+00", "10.00");
        insertDocument(fixture, latestMixedSale, "T-2", "2026-08-07 10:02:00+00", "5.00");
        insertProductLine(fixture, latestMixedSale, UUID.randomUUID(), "1.000", "5.00", null);
        jdbc.update("""
                insert into documento_relacion (documento_id, origen_id, tipo)
                values (?, ?, 'COMPENSA')
                """, latestMixedSale, returnDocument);

        assertThat(documents.findLatestPositiveConfirmedTicketIds(
                fixture.storeId(), fixture.terminalId(), PageRequest.of(0, 1)))
                .containsExactly(previousSale);
    }

    @Test
    void priceEvidenceQueryReturnsOnlyLatestTimestampAndAllItsTiesInBulk() {
        var fixture = insertFixture();
        var secondProductId = UUID.randomUUID();
        jdbc.update("""
                insert into producto (id, tienda_id, familia_id, impuesto_id, nombre)
                values (?, ?, ?, ?, 'Producto 2')
                """, secondProductId, fixture.storeId(), fixture.familyId(), fixture.taxId());
        insertPrice(fixture.productId(), "5.00", "2026-08-07 09:00:00+00");
        var latestFirst = insertPrice(
                fixture.productId(), "7.00", "2026-08-07 10:00:00+00");
        var latestTie = insertPrice(
                fixture.productId(), "7.00", "2026-08-07 10:00:00+00");
        insertPrice(fixture.productId(), "9.00", "2026-08-07 11:00:00+00");
        var secondLatest = insertPrice(
                secondProductId, "3.00", "2026-08-07 09:30:00+00");

        var result = priceHistory.findPriceEvidenceAtOrBefore(
                List.of(fixture.productId(), secondProductId),
                ProductPriceHistoryType.VENTA,
                Instant.parse("2026-08-07T10:30:00Z"));

        assertThat(result).extracting(value -> value.getId())
                .containsExactlyInAnyOrder(latestFirst, latestTie, secondLatest);
        assertThat(result).extracting(value -> value.getAmount())
                .containsExactlyInAnyOrder(
                        new BigDecimal("7.00"), new BigDecimal("7.00"),
                        new BigDecimal("3.00"));
    }

    private UUID insertPrice(UUID productId, String amount, String updatedAt) {
        var id = UUID.randomUUID();
        jdbc.update("""
                insert into producto_precio_historial (
                    id, producto_id, tipo, importe, actualizado_en)
                values (?, ?, 'VENTA', ?, cast(? as timestamptz))
                """, id, productId, new BigDecimal(amount), updatedAt);
        return id;
    }

    private Fixture insertFixture() {
        var fixture = new Fixture(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("""
                insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                values (?, 'B00000000', 'Company', cast(? as jsonb))
                """, fixture.companyId(), address());
        jdbc.update("""
                insert into tienda (
                    id, empresa_id, codigo_tienda, nombre, direccion,
                    address_normalized_hash, timezone, moneda, locale)
                values (?, ?, '001', 'Store', cast(? as jsonb), 'hash',
                    'Atlantic/Canary', 'EUR', 'es-ES')
                """, fixture.storeId(), fixture.companyId(), address());
        jdbc.update("""
                insert into terminal (id, tienda_id, nombre, tipo, credential_hash)
                values (?, ?, 'TPV', 'TERMINAL_VENTA', 'hash')
                """, fixture.terminalId(), fixture.storeId());
        jdbc.update("""
                insert into rol (id, tienda_id, nombre, protegido)
                values (?, ?, 'ADMIN', true)
                """, fixture.roleId(), fixture.storeId());
        jdbc.update("""
                insert into usuario (
                    id, tienda_id, nombre, user_name, password_hash, rol_id, protegido)
                values (?, ?, 'ADMIN', 'ADMIN', 'hash', ?, true)
                """, fixture.userId(), fixture.storeId(), fixture.roleId());
        jdbc.update("""
                insert into impuesto_tienda (id, tienda_id, porcentaje)
                values (?, ?, 21)
                """, fixture.taxId(), fixture.storeId());
        jdbc.update("""
                insert into familia (id, tienda_id, nombre)
                values (?, ?, 'GENERAL')
                """, fixture.familyId(), fixture.storeId());
        jdbc.update("""
                insert into almacen (id, tienda_id, nombre, predeterminado)
                values (?, ?, 'GENERAL', true)
                """, fixture.warehouseId(), fixture.storeId());
        jdbc.update("""
                insert into producto (id, tienda_id, familia_id, impuesto_id, nombre)
                values (?, ?, ?, ?, 'Producto')
                """, fixture.productId(), fixture.storeId(), fixture.familyId(),
                fixture.taxId());
        return fixture;
    }

    private void insertDocument(
            Fixture fixture,
            UUID documentId,
            String number,
            String confirmedAt,
            String total) {
        var amount = new BigDecimal(total);
        jdbc.update("""
                insert into documento (
                    id, tienda_id, almacen_id, tipo, estado, numero, fecha,
                    creado_en, confirmado_en, creado_por, confirmado_por,
                    descuento_global, base_total, impuesto_total, total,
                    moneda, origen_stock, terminal_origen_id)
                values (?, ?, ?, 'TICKET', 'CONFIRMADO', ?, current_date,
                    cast(? as timestamptz), cast(? as timestamptz), ?, ?,
                    0, ?, 0, ?, 'EUR', true, ?)
                """, documentId, fixture.storeId(), fixture.warehouseId(), number,
                confirmedAt, confirmedAt, fixture.userId(), fixture.userId(),
                amount, amount, fixture.terminalId());
    }

    private void insertProductLine(
            Fixture fixture,
            UUID documentId,
            UUID lineId,
            String quantity,
            String total,
            UUID originalLineId) {
        var quantityValue = new BigDecimal(quantity);
        var amount = new BigDecimal(total);
        jdbc.update("""
                insert into documento_linea (
                    id, documento_id, producto_id, tipo_linea, posicion, cantidad,
                    codigo, nombre, tarifa, precio_unitario, descuento,
                    impuestos_incluidos, regimen_impuesto, porcentaje_impuesto,
                    base, impuesto, total, original_document_line_id)
                values (?, ?, ?, 'PRODUCT', 1, ?, 'P-1', 'Producto', 'VENTA',
                    ?, 0, true, 'IVA', 0, ?, 0, ?, ?)
                """, lineId, documentId, fixture.productId(), quantityValue, amount,
                amount, amount, originalLineId);
    }

    private static String required(String name) {
        var value = System.getenv(name);
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

    private record Fixture(
            UUID companyId,
            UUID storeId,
            UUID terminalId,
            UUID roleId,
            UUID userId,
            UUID taxId,
            UUID familyId,
            UUID warehouseId,
            UUID productId) {
    }
}

package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("test")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FlywayPostgreSqlConfiguration.class)
class SalesActivityDailyRepositoryPostgreSqlTest {

    private static final String SCHEMA =
            "sales_activity_daily_" + UUID.randomUUID().toString().replace("-", "");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private CommercialDocumentRepository documents;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> databaseUrl()
                + (databaseUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> environment(
                "TPV_TEST_DB_USERNAME", "tpv_erp_test"));
        registry.add("spring.datasource.password", () -> environment(
                "TPV_TEST_DB_PASSWORD", "admin"));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @AfterAll
    static void dropSchema() {
        execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    void groupsLogicalDocumentsByDayWithoutDoubleCountingDerivedInvoices() {
        var fixture = insertFixture();
        var newest = LocalDate.of(2026, 8, 31);
        var previous = newest.minusDays(1);

        var ticket = insertDocument(fixture.primary(), CommercialDocumentType.TICKET,
                DocumentStatus.CONFIRMADO, "T-1", newest, "10.00");
        var derivedInvoice = insertDocument(fixture.primary(),
                CommercialDocumentType.FACTURA_VENTA, DocumentStatus.CONFIRMADO,
                "F-1", newest, "10.00");
        jdbc.update("""
                insert into documento_relacion (documento_id, origen_id, tipo)
                values (?, ?, 'FACTURA_DE')
                """, derivedInvoice, ticket);
        insertDocument(fixture.primary(), CommercialDocumentType.FACTURA_VENTA,
                DocumentStatus.CONFIRMADO, "F-2", newest, "5.00");
        insertDocument(fixture.primary(), CommercialDocumentType.RECTIFICATIVA_VENTA,
                DocumentStatus.CONFIRMADO, "R-1", newest, "2.00");
        insertDocument(fixture.primary(), CommercialDocumentType.RECTIFICATIVA_VENTA,
                DocumentStatus.CONFIRMADO, "R-2", newest, "-4.00");
        insertDocument(fixture.primary(), CommercialDocumentType.TICKET,
                DocumentStatus.ANULADO, "T-2", newest, "9.00");
        insertDocument(fixture.primary(), CommercialDocumentType.TICKET,
                DocumentStatus.BORRADOR, "T-DRAFT", newest, "99.00");
        insertDocument(fixture.secondary(), CommercialDocumentType.TICKET,
                DocumentStatus.CONFIRMADO, "T-OTHER", newest, "100.00");
        insertDocument(fixture.primary(), CommercialDocumentType.TICKET,
                DocumentStatus.CONFIRMADO, "T-3", previous, "3.00");

        var firstPage = documents.findSalesActivityDaily(
                fixture.primary().storeId(), previous, newest, PageRequest.of(0, 1));
        assertThat(firstPage).singleElement().satisfies(row -> {
            assertThat(row.getDate()).isEqualTo(newest);
            assertThat(row.getTicketCount()).isEqualTo(2L);
            assertThat(row.getInvoiceCount()).isEqualTo(4L);
            assertThat(row.getTotal()).isEqualByComparingTo("13.00");
        });

        var nextPage = documents.findSalesActivityDailyAfter(
                fixture.primary().storeId(), previous, newest, newest, PageRequest.of(0, 1));
        assertThat(nextPage).singleElement().satisfies(row -> {
            assertThat(row.getDate()).isEqualTo(previous);
            assertThat(row.getTicketCount()).isEqualTo(1L);
            assertThat(row.getInvoiceCount()).isEqualTo(0L);
            assertThat(row.getTotal()).isEqualByComparingTo("3.00");
        });

        var totals = documents.sumSalesActivityDaily(
                fixture.primary().storeId(), previous, newest);
        assertThat(totals.getTicketCount()).isEqualTo(3L);
        assertThat(totals.getInvoiceCount()).isEqualTo(4L);
        assertThat(totals.getTotal()).isEqualByComparingTo("16.00");
    }

    private Fixture insertFixture() {
        var companyId = UUID.randomUUID();
        jdbc.update("""
                insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                values (?, 'B00000000', 'Company', cast(? as jsonb))
                """, companyId, address());
        return new Fixture(insertStore(companyId, "001"), insertStore(companyId, "002"));
    }

    private StoreFixture insertStore(UUID companyId, String code) {
        var storeId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        jdbc.update("""
                insert into tienda (
                    id, empresa_id, codigo_tienda, nombre, direccion,
                    address_normalized_hash, timezone, moneda, locale)
                values (?, ?, ?, ?, cast(? as jsonb), ?, 'Atlantic/Canary', 'EUR', 'es-ES')
                """, storeId, companyId, code, "Store " + code, address(),
                "hash-" + code);
        jdbc.update("""
                insert into rol (id, tienda_id, nombre, protegido)
                values (?, ?, ?, true)
                """, roleId, storeId, "ADMIN-" + code);
        jdbc.update("""
                insert into usuario (
                    id, tienda_id, nombre, user_name, password_hash, rol_id, protegido)
                values (?, ?, ?, ?, 'hash', ?, true)
                """, userId, storeId, "ADMIN " + code, "ADMIN-" + code, roleId);
        jdbc.update("""
                insert into almacen (id, tienda_id, nombre, predeterminado)
                values (?, ?, 'GENERAL', true)
                """, warehouseId, storeId);
        return new StoreFixture(storeId, warehouseId, userId);
    }

    private UUID insertDocument(
            StoreFixture fixture,
            CommercialDocumentType type,
            DocumentStatus status,
            String number,
            LocalDate date,
            String total) {
        var id = UUID.randomUUID();
        var cancelled = status == DocumentStatus.ANULADO;
        jdbc.update("""
                insert into documento (
                    id, tienda_id, almacen_id, tipo, estado, numero, fecha,
                    creado_en, confirmado_en, anulado_en, creado_por,
                    confirmado_por, anulado_por, motivo_anulacion,
                    descuento_global, base_total, impuesto_total, total,
                    moneda, origen_stock)
                values (?, ?, ?, ?, ?, ?, ?, now(), now(), ?, ?, ?, ?, ?,
                    0, ?, 0, ?, 'EUR', false)
                """, id, fixture.storeId(), fixture.warehouseId(), type.name(),
                status.name(), number, date,
                cancelled ? java.sql.Timestamp.from(java.time.Instant.now()) : null,
                fixture.userId(), fixture.userId(), cancelled ? fixture.userId() : null,
                cancelled ? "Test" : null, new BigDecimal(total), new BigDecimal(total));
        return id;
    }

    private static String address() {
        return "{\"linea1\":\"CALLE TEST 1\",\"ciudad\":\"LAS PALMAS\","
                + "\"codigoPostal\":\"35001\",\"provincia\":\"LAS PALMAS\","
                + "\"pais\":\"ES\"}";
    }

    private static String databaseUrl() {
        return environment("TPV_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
    }

    private static String environment(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(
                databaseUrl(),
                environment("TPV_TEST_DB_USERNAME", "tpv_erp_test"),
                environment("TPV_TEST_DB_PASSWORD", "admin"));
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(StoreFixture primary, StoreFixture secondary) {
    }

    private record StoreFixture(UUID storeId, UUID warehouseId, UUID userId) {
    }
}

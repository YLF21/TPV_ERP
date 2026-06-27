package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.tpverp.backend.cash.CashPaymentRecorder;
import com.tpverp.backend.catalog.ProductSupplierRepository;
import com.tpverp.backend.inventory.InventoryDocumentGateway;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.CompanyRepository;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        DocumentService.class,
        InventoryDocumentGateway.class,
        DocumentConfirmationRollbackPostgreSqlTest.Configuration.class
})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class DocumentConfirmationRollbackPostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA =
            "tpv_erp_document_" + UUID.randomUUID().toString().replace("-", "");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private DocumentService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StoreRepository stores;
    @Autowired private CompanyRepository companies;
    @Autowired private UserAccountRepository users;
    @Autowired private TransactionTemplate transactions;
    @MockitoBean private CurrentOrganization organization;
    @MockitoBean private DocumentFiscalIntegration fiscalIntegration;
    @MockitoBean private VoucherService voucherService;
    @MockitoBean private CurrentTerminal currentTerminal;
    @MockitoBean private CashPaymentRecorder cashPayments;

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
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void recorderFailureRollsBackDocumentCounterStockMovementAndSupplierLink() {
        Fixture ids = insertFixture();
        var store = stores.findById(ids.storeId()).orElseThrow();
        var company = companies.findById(ids.companyId()).orElseThrow();
        var user = users.findById(ids.userId()).orElseThrow();
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentUser(any())).thenReturn(user);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored ->
                service.confirm(
                        ids.documentId(),
                        new UsernamePasswordAuthenticationToken("ADMIN", "n/a"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fallo posterior al UPSERT");

        var document = jdbc.queryForMap("""
                select estado, numero, confirmado_en, origen_stock
                from %s.documento where id = ?
                """.formatted(SCHEMA), ids.documentId());
        assertThat(document.get("estado")).isEqualTo("BORRADOR");
        assertThat(document.get("numero")).isNull();
        assertThat(document.get("confirmado_en")).isNull();
        assertThat(document.get("origen_stock")).isEqualTo(true);
        assertCount("contador_documento", 0);
        assertCount("existencia", 0);
        assertCount("movimiento_stock", 0);
        assertCount("producto_proveedor", 0);
    }

    private void assertCount(String table, int expected) {
        assertThat(jdbc.queryForObject(
                "select count(*) from " + SCHEMA + "." + table, Integer.class))
                .isEqualTo(expected);
    }

    private Fixture insertFixture() {
        var ids = new Fixture(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID());
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
                    id, tienda_id, nombre, password_hash, rol_id, protegido)
                values (?, ?, 'ADMIN', 'hash', ?, true)
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
        jdbc.update("""
                insert into %s.producto (
                    id, tienda_id, familia_id, impuesto_id, nombre)
                values (?, ?, ?, ?, 'Producto')
                """.formatted(SCHEMA), ids.productId(), ids.storeId(),
                ids.familyId(), ids.taxId());
        jdbc.update("""
                insert into %s.proveedor (
                    id, empresa_id, razon_social, tipo_documento, numero_documento,
                    code_supplier)
                values (?, ?, 'Proveedor', 'CIF', 'B00000001', 'S-000001')
                """.formatted(SCHEMA), ids.supplierId(), ids.companyId());
        jdbc.update("""
                insert into %s.documento (
                    id, tienda_id, almacen_id, tipo, estado, fecha, creado_en,
                    creado_por, proveedor_id, descuento_global, base_total,
                    impuesto_total, total, moneda, origen_stock)
                values (?, ?, ?, 'ALBARAN_COMPRA', 'BORRADOR', ?, ?, ?, ?,
                    0, 10, 2.10, 12.10, 'EUR', true)
                """.formatted(SCHEMA),
                ids.documentId(), ids.storeId(), ids.warehouseId(),
                LocalDate.of(2026, 6, 12),
                java.sql.Timestamp.from(Instant.parse("2026-06-12T12:00:00Z")),
                ids.userId(), ids.supplierId());
        jdbc.update("""
                insert into %s.documento_linea (
                    id, documento_id, producto_id, posicion, cantidad, codigo,
                    nombre, tarifa, precio_unitario, descuento,
                    impuestos_incluidos, regimen_impuesto, porcentaje_impuesto,
                    base, impuesto, total)
                values (?, ?, ?, 1, 1, 'P-1', 'Producto', 'VENTA', 12.10, 0,
                    true, 'IVA', 21, 10, 2.10, 12.10)
                """.formatted(SCHEMA),
                UUID.randomUUID(), ids.documentId(), ids.productId());
        return ids;
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

    @TestConfiguration
    static class Configuration {

        @Bean
        Clock clock() {
            return Clock.fixed(
                    Instant.parse("2026-06-12T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        ConfirmedPurchaseRecorder failingRecorder(ProductSupplierRepository links) {
            return (supplierId, date, productIds) -> {
                productIds.forEach(productId -> links.upsertPurchase(
                        UUID.randomUUID(), productId, supplierId, date));
                throw new IllegalStateException("fallo posterior al UPSERT");
            };
        }
    }

    private record Fixture(
            UUID companyId,
            UUID storeId,
            UUID roleId,
            UUID userId,
            UUID taxId,
            UUID familyId,
            UUID warehouseId,
            UUID productId,
            UUID supplierId,
            UUID documentId) {
    }
}

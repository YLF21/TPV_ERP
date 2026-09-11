package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.catalog.CatalogService;
import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.Family;
import com.tpverp.backend.catalog.FamilyProductPageRepository;
import com.tpverp.backend.catalog.PriceUseMode;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.catalog.StoreTax;
import com.tpverp.backend.catalog.StoreTaxRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Real CatalogService + ProductRepository proof of all-or-nothing missing-product creation. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, CatalogService.class,
        ProductExcelImportApplyWriter.class, ProductExcelImportApplyWriterCatalogPostgreSqlTest.Configuration.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class ProductExcelImportApplyWriterCatalogPostgreSqlTest {

    private static final String URL = environment("TPV_ERP_TEST_DB_URL");
    private static final String USER = environment("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = environment("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "excel_apply_catalog_"
            + UUID.randomUUID().toString().replace("-", "");

    static {
        if (!URL.isBlank() && !USER.isBlank() && !PASSWORD.isBlank()) execute("create schema " + SCHEMA);
    }

    @Autowired private ProductExcelImportApplyWriter writer;
    @Autowired private ProductRepository products;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentOrganization organization;
    @MockitoBean private AuditService auditService;
    @MockitoBean private FamilyProductPageRepository familyProductPageRepository;

    private UUID storeId;
    private UUID familyId;
    private UUID taxId;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ProductExcelImportApplyWriterCatalogPostgreSqlTest::schemaUrl);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void fixture() {
        UUID companyId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        familyId = UUID.randomUUID();
        taxId = UUID.randomUUID();
        insertCompany(companyId);
        insertStore(companyId, storeId);
        insertFamily(storeId, familyId);
        jdbc.update("insert into impuesto_tienda (id, tienda_id, porcentaje) values (?, ?, 21)", taxId, storeId);
        jdbc.execute("create sequence if not exists excel_apply_catalog_attempts");
        jdbc.execute("alter sequence excel_apply_catalog_attempts restart with 1");
        jdbc.execute("""
                create or replace function excel_apply_catalog_fail_second() returns trigger
                language plpgsql as $$
                begin
                    perform nextval('excel_apply_catalog_attempts');
                    if new.nombre = 'FAIL_SECOND' then
                        raise exception 'forced second catalog insert failure';
                    end if;
                    return new;
                end $$
                """);
        jdbc.execute("drop trigger if exists trg_excel_apply_catalog_fail_second on producto");
        jdbc.execute("""
                create trigger trg_excel_apply_catalog_fail_second
                before insert on producto
                for each row execute function excel_apply_catalog_fail_second()
                """);
        jdbc.execute("""
                create or replace function excel_apply_catalog_fail_update() returns trigger
                language plpgsql as $$
                begin
                    if new.precio_compra is distinct from old.precio_compra then
                        -- Hibernate can order updates by product UUID, not Excel row.
                        if nextval('excel_apply_catalog_update_attempts') = 2 then
                            raise exception 'forced second catalog update failure';
                        end if;
                    end if;
                    return new;
                end $$
                """);
        jdbc.execute("create sequence if not exists excel_apply_catalog_update_attempts");
        jdbc.execute("alter sequence excel_apply_catalog_update_attempts restart with 1");
        jdbc.execute("drop trigger if exists trg_excel_apply_catalog_fail_update on producto");
        jdbc.execute("""
                create trigger trg_excel_apply_catalog_fail_update
                before update on producto
                for each row execute function excel_apply_catalog_fail_update()
                """);
        Store store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
    }

    @AfterAll
    static void dropSchema() {
        if (!URL.isBlank() && !USER.isBlank() && !PASSWORD.isBlank()) execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void realCatalogBatchRollsBackFirstCreationWhenSecondFlushFails() {
        var requests = List.of(request("FIRST_OK", "first"), request("SECOND", "FAIL_SECOND"));
        var items = List.of(item(2, requests.get(0)), item(3, requests.get(1)));

        assertThatThrownBy(() -> writer.write(items)).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("forced second catalog insert failure");

        Integer count = jdbc.queryForObject("select count(*) from producto where tienda_id = ?", Integer.class, storeId);
        org.assertj.core.api.Assertions.assertThat(count).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select last_value from excel_apply_catalog_attempts", Long.class)).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from producto_identificador where tienda_id = ?", Integer.class, storeId)).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void commitsTheCompleteBatchThroughTheRealCatalog() {
        var applied = writer.write(List.of(item(2, request("SUCCESS_FIRST", "First")), item(3, request("SUCCESS_SECOND", "Second"))));
        org.assertj.core.api.Assertions.assertThat(applied).hasSize(2).allSatisfy(item ->
                org.assertj.core.api.Assertions.assertThat(item.mutated()).isTrue());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from producto where tienda_id = ?", Integer.class, storeId)).isEqualTo(2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void persistsThreeDecimalCatalogPricesAndExtendsBeforeRoundingInPostgres() {
        var request = new CatalogService.ProductRequest(familyId, null, taxId, ProductType.UNIT,
                DiscountType.NORMAL, PriceUseMode.NORMAL, "Precision", null, null, new BigDecimal("1.104"),
                true, "PRECISION_3", null, null, new BigDecimal("2.208"), new BigDecimal("1.192"),
                new BigDecimal("1.584"), new BigDecimal("1.152"), null, null, false, null, null, null, null);
        writer.write(List.of(item(2, request)));
        UUID id = (UUID) productByCode("PRECISION_3").get("id");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select precio_compra from producto where id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("1.104");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "select importe from producto_precio where producto_id = ?", BigDecimal.class, id))
                .extracting(BigDecimal::toPlainString).contains("2.208", "1.192", "1.584", "1.152");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select round(importe * 10, 2) from producto_precio where producto_id = ? and tarifa = 'VENTA'",
                BigDecimal.class, id)).isEqualByComparingTo("22.08");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.columns where table_schema = ?
                and numeric_precision = 20 and numeric_scale = 3
                and (table_name, column_name) in (
                    ('producto','precio_compra'), ('producto_precio','importe'),
                    ('producto_precio_historial','importe'), ('producto_proveedor','precio_compra_bruto'),
                    ('entrada_almacen_linea','precio_unitario_compra'), ('salida_almacen_linea','precio_unitario_venta'),
                    ('documento_linea','precio_unitario'), ('venta_linea_eliminada','precio_unitario'),
                    ('autorizacion_cambio_precio_venta','precio_unitario'))
                """, Integer.class, SCHEMA)).isEqualTo(9);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollsBackPurchaseUpdatesForAllRowsWhenTheSecondRealUpdateFails() {
        writer.write(List.of(item(2, request("UPDATE_FIRST", "First")),
                item(3, request("UPDATE_SECOND", "FAIL_UPDATE"))));
        List<Map<String, Object>> before = jdbc.queryForList("""
                select p.id, p.version, p.precio_compra, pi.valor as code
                from producto p join producto_identificador pi on pi.producto_id = p.id
                where p.tienda_id = ? and pi.valor in ('UPDATE_FIRST', 'UPDATE_SECOND')
                order by pi.valor
                """, storeId);
        Map<String, Object> first = before.stream().filter(row -> "UPDATE_FIRST".equals(row.get("code"))).findFirst().orElseThrow();
        Map<String, Object> second = before.stream().filter(row -> "UPDATE_SECOND".equals(row.get("code"))).findFirst().orElseThrow();
        UUID firstId = (UUID) first.get("id");
        UUID secondId = (UUID) second.get("id");
        long firstVersion = ((Number) first.get("version")).longValue();
        long secondVersion = ((Number) second.get("version")).longValue();

        var firstUpdate = updateItem(10, firstId, firstVersion, request("UPDATE_FIRST", "First", "25.00"));
        var secondUpdate = updateItem(11, secondId, secondVersion, request("UPDATE_SECOND", "FAIL_UPDATE", "30.00"));
        assertThatThrownBy(() -> writer.write(List.of(firstUpdate, secondUpdate)))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("forced second catalog update failure");

        Map<String, Object> afterFirst = jdbc.queryForMap("select version, precio_compra from producto where id = ?", firstId);
        Map<String, Object> afterSecond = jdbc.queryForMap("select version, precio_compra from producto where id = ?", secondId);
        org.assertj.core.api.Assertions.assertThat(afterFirst.get("version")).isEqualTo(firstVersion);
        org.assertj.core.api.Assertions.assertThat(afterSecond.get("version")).isEqualTo(secondVersion);
        org.assertj.core.api.Assertions.assertThat(afterFirst.get("precio_compra")).isEqualTo(new BigDecimal("10.000"));
        org.assertj.core.api.Assertions.assertThat(afterSecond.get("precio_compra")).isEqualTo(new BigDecimal("10.000"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select last_value from excel_apply_catalog_update_attempts", Long.class)).isEqualTo(2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void updatesOnlyPurchaseAndPreservesNameSalePriceAndCode() {
        writer.write(List.of(item(2, request("UPDATE_SUCCESS", "Original"))));
        Map<String, Object> before = productByCode("UPDATE_SUCCESS");
        UUID id = (UUID) before.get("id");
        long version = ((Number) before.get("version")).longValue();

        writer.write(List.of(updateItem(10, id, version, request("UPDATE_SUCCESS", "Original", "25.00"))));

        Map<String, Object> after = productByCode("UPDATE_SUCCESS");
        org.assertj.core.api.Assertions.assertThat(after.get("nombre")).isEqualTo("ORIGINAL");
        org.assertj.core.api.Assertions.assertThat(after.get("precio_compra")).isEqualTo(new BigDecimal("25.000"));
        org.assertj.core.api.Assertions.assertThat(after.get("version")).isEqualTo(version + 1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select valor from producto_identificador where producto_id = ? and valor = 'UPDATE_SUCCESS'", String.class, id))
                .isEqualTo("UPDATE_SUCCESS");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select importe from producto_precio where producto_id = ? and tarifa = 'VENTA'", BigDecimal.class, id))
                .isEqualTo(new BigDecimal("20.000"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void staleVersionRejectsUpdateWithoutMutation() {
        writer.write(List.of(item(2, request("STALE_CODE", "Stale"))));
        Map<String, Object> before = productByCode("STALE_CODE");
        UUID id = (UUID) before.get("id");
        long version = ((Number) before.get("version")).longValue();

        assertThatThrownBy(() -> writer.write(List.of(updateItem(10, id, version + 1,
                request("STALE_CODE", "Stale", "99.00"))))
        ).isInstanceOf(ProductExcelImportApplyWriter.StaleVersionException.class);

        Map<String, Object> after = productByCode("STALE_CODE");
        org.assertj.core.api.Assertions.assertThat(after.get("version")).isEqualTo(version);
        org.assertj.core.api.Assertions.assertThat(after.get("precio_compra")).isEqualTo(new BigDecimal("10.000"));
    }

    private CatalogService.ProductRequest request(String code, String name) {
        return request(code, name, "10.00");
    }

    private CatalogService.ProductRequest request(String code, String name, String purchasePrice) {
        return new CatalogService.ProductRequest(familyId, null, taxId, ProductType.UNIT,
                DiscountType.NORMAL, PriceUseMode.NORMAL, name, null, null, new BigDecimal(purchasePrice),
                true, code, null, null, new BigDecimal("20.00"), null, null, null, null, null,
                false, null, null, null, null);
    }

    private ProductExcelImportApplyService.WriteItem item(int rowNumber, CatalogService.ProductRequest request) {
        var row = new ProductExcelImportPreviewService.PreviewRow(rowNumber, List.of(rowNumber), "MISSING",
                Map.of("code", request.code(), "name", request.name()), null, null, Map.of(), List.of());
        return new ProductExcelImportApplyService.WriteItem(row, null, request);
    }

    private ProductExcelImportApplyService.WriteItem updateItem(int rowNumber, UUID id, long version,
            CatalogService.ProductRequest request) {
        var row = new ProductExcelImportPreviewService.PreviewRow(rowNumber, List.of(rowNumber), "EXISTING",
                Map.of("code", request.code(), "name", request.name(), "purchasePrice", "25.00"),
                Map.of("id", id.toString()), version,
                Map.of("purchasePrice", Map.of("before", "10.00", "after", "25.00")), List.of());
        return new ProductExcelImportApplyService.WriteItem(row,
                new ProductExcelImportApplyService.ExpectedProduct(id, version), request);
    }

    private Map<String, Object> productByCode(String code) {
        return jdbc.queryForMap("""
                select p.id, p.version, p.nombre, p.precio_compra
                from producto p join producto_identificador pi on pi.producto_id = p.id
                where p.tienda_id = ? and pi.valor = ?
                """, storeId, code);
    }

    private void insertCompany(UUID id) {
        jdbc.update("insert into empresa (id, tax_id, razon_social, domicilio_fiscal) values (?, ?, 'Excel test', cast(? as jsonb))",
                id, "B" + id.toString().replace("-", "").substring(0, 8), address());
    }

    private void insertStore(UUID companyId, UUID id) {
        jdbc.update("""
                insert into tienda (id, empresa_id, codigo_tienda, nombre, direccion,
                  address_normalized_hash, timezone, moneda, locale)
                values (?, ?, '001', 'Excel test store', cast(? as jsonb), ?, 'Atlantic/Canary', 'EUR', 'es-ES')
                """, id, companyId, address(), "excel-" + id);
    }

    private void insertFamily(UUID store, UUID id) {
        jdbc.update("""
                insert into familia (id, tienda_id, family_id, family_code, nombre, predeterminada)
                values (?, ?, 'EXCEL', '001', 'Excel family', false)
                """, id, store);
    }

    private static String address() {
        return "{\"linea1\":\"Test\",\"ciudad\":\"Las Palmas\",\"codigoPostal\":\"35001\","
                + "\"provincia\":\"Las Palmas\",\"pais\":\"ES\"}";
    }

    private static String schemaUrl() {
        return URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public";
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("No se pudo preparar PostgreSQL aislado", exception);
        }
    }

    @TestConfiguration
    static class Configuration {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-07T10:15:30Z"), ZoneOffset.UTC);
        }
    }

}

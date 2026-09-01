package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, CatalogService.class,
        ProductBulkXlsxService.class, ProductBulkXlsxCatalogPostgreSqlTest.Configuration.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class ProductBulkXlsxCatalogPostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "product_bulk_xlsx_catalog_"
            + UUID.randomUUID().toString().replace("-", "");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private ProductBulkXlsxService xlsx;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentOrganization organization;
    @MockitoBean private AuditService auditService;
    @MockitoBean private FamilyProductPageRepository familyProductPageRepository;

    private UUID storeId;
    private UUID familyId;
    private UUID subfamilyId;
    private UUID otherStoreFamilyId;

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

    @BeforeEach
    void fixture() {
        UUID companyId = UUID.randomUUID();
        UUID otherCompanyId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        UUID otherStoreId = UUID.randomUUID();
        familyId = UUID.randomUUID();
        subfamilyId = UUID.randomUUID();
        otherStoreFamilyId = UUID.randomUUID();
        insertCompany(companyId, "B23730001");
        insertCompany(otherCompanyId, "B23730002");
        insertStore(storeId, companyId, "403");
        insertStore(otherStoreId, otherCompanyId, "404");
        insertFamily(familyId, storeId, "001", "BEBIDAS");
        insertSubfamily(subfamilyId, familyId, "001", "001001", "AGUA");
        insertFamily(otherStoreFamilyId, otherStoreId, "001", "OTRA TIENDA");
        Store store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
    }

    @AfterAll
    static void dropSchema() {
        execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    void derivesAuthoritativeCodesIntoWorkbookWhenLegacyCallerOmitsMaps() throws Exception {
        ProductBulkEditContent.Row row = row(familyId, subfamilyId);
        ProductBulkXlsxContent request = new ProductBulkXlsxContent(
                List.of(row), ProductBulkXlsxContent.HeaderLanguage.ES, Map.of(), Map.of());
        var output = new ByteArrayOutputStream();

        xlsx.export(request, output);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            var product = workbook.getSheet("Productos").getRow(1);
            assertThat(product.getCell(23).getStringCellValue()).isEqualTo("001");
            assertThat(product.getCell(24).getStringCellValue()).isEqualTo(familyId.toString());
            assertThat(product.getCell(26).getStringCellValue()).isEqualTo("001001");
            assertThat(product.getCell(27).getStringCellValue()).isEqualTo(subfamilyId.toString());
        }
    }

    @Test
    void exportsRowsWithoutSubfamilyAndRowsWhoseDraftClearsTheSubfamily() throws Exception {
        ProductBulkEditContent.Row withoutSubfamily = row(familyId, null);
        ProductBulkEditContent.Row classified = row(familyId, subfamilyId);
        ProductBulkEditContent.Row cleared = withDraftSubfamily(classified, "-");
        ProductBulkEditContent.Row blank = withDraftSubfamily(
                row(familyId, subfamilyId), "   ");
        ProductBulkXlsxContent request = new ProductBulkXlsxContent(
                List.of(withoutSubfamily, cleared, blank),
                ProductBulkXlsxContent.HeaderLanguage.ES, Map.of(), Map.of());
        var output = new ByteArrayOutputStream();

        xlsx.export(request, output);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            var products = workbook.getSheet("Productos");
            assertThat(products.getRow(1).getCell(23).getStringCellValue()).isEqualTo("001");
            assertThat(products.getRow(1).getCell(26)).isNull();
            assertThat(products.getRow(1).getCell(27)).isNull();
            assertThat(products.getRow(2).getCell(23).getStringCellValue()).isEqualTo("001");
            assertThat(products.getRow(2).getCell(26)).isNull();
            assertThat(products.getRow(2).getCell(27)).isNull();
            assertThat(products.getRow(3).getCell(23).getStringCellValue()).isEqualTo("001");
            assertThat(products.getRow(3).getCell(26)).isNull();
            assertThat(products.getRow(3).getCell(27)).isNull();
        }
    }

    @Test
    void rejectsForgedCodesAndCatalogRowsFromAnotherStore() {
        ProductBulkEditContent.Row local = row(familyId, subfamilyId);
        var forged = new ProductBulkXlsxContent(
                List.of(local), ProductBulkXlsxContent.HeaderLanguage.ES,
                Map.of(familyId.toString(), "999"),
                Map.of(subfamilyId.toString(), "999999"));
        assertThatThrownBy(() -> xlsx.export(forged, new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no coincide");

        ProductBulkEditContent.Row foreign = row(otherStoreFamilyId, null);
        var crossStore = new ProductBulkXlsxContent(
                List.of(foreign), ProductBulkXlsxContent.HeaderLanguage.ES,
                Map.of(), Map.of());
        assertThatThrownBy(() -> xlsx.export(crossStore, new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no cubre");
    }

    private static ProductBulkEditContent.Row row(UUID family, UUID subfamily) {
        ProductBulkEditContent.ProductData product = new ProductBulkEditContent.ProductData(
                UUID.randomUUID(), 0L, null, null,
                "P-1", null, null, "PRODUCTO", null, null,
                "1.00", null, "2.00", null, null, null, null,
                ProductType.UNIT.name(), PriceUseMode.NORMAL.name(), DiscountType.NORMAL.name(),
                family.toString(), "NOMBRE CLIENTE NO AUTORITATIVO",
                subfamily == null ? null : subfamily.toString(),
                subfamily == null ? null : "SUBFAMILIA CLIENTE",
                UUID.randomUUID().toString(), "IGIC", "true", "false",
                null, null, null, null, null);
        return new ProductBulkEditContent.Row(
                "row-" + UUID.randomUUID(), true, "P-1", product,
                ProductBulkEditContent.ProductData.empty(), List.of(), null);
    }

    private static ProductBulkEditContent.Row withDraftSubfamily(
            ProductBulkEditContent.Row row, String subfamilyId) {
        ProductBulkEditContent.ProductData source = row.product();
        ProductBulkEditContent.ProductData draft = new ProductBulkEditContent.ProductData(
                source.productId(), source.version(), source.imageId(), source.warehouseId(),
                source.code(), source.barcode(), source.barcode2(), source.name(),
                source.description(), source.comments(), source.purchasePrice(),
                source.purchaseDiscountPercent(), source.salePrice(), source.memberPrice(),
                source.wholesalePrice(), source.offerPrice(), source.offerDiscountPercent(),
                source.productType(), source.discountType(), source.backendDiscountType(),
                source.familyId(), source.familyName(), subfamilyId, subfamilyId,
                source.taxId(), source.taxName(), source.taxesIncluded(), source.offerActive(),
                source.offerFrom(), source.offerUntil(), source.warehouseName(), source.quantity(),
                source.totalQuantity(), source.stockMin(), source.stockMax(), source.active(),
                source.packageQuantity());
        return new ProductBulkEditContent.Row(
                row.id() + "-cleared", row.selected(), row.query(), source,
                draft, row.suppliers(), row.pendingSupplier());
    }

    private void insertCompany(UUID id, String taxId) {
        jdbc.update("""
                insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                values (?, ?, 'Empresa xlsx', cast(? as jsonb))
                """, id, taxId, address());
    }

    private void insertStore(UUID id, UUID companyId, String code) {
        jdbc.update("""
                insert into tienda (id, empresa_id, codigo_tienda, nombre, direccion,
                  address_normalized_hash, timezone, moneda, locale)
                values (?, ?, ?, 'Tienda xlsx', cast(? as jsonb), ?,
                  'Atlantic/Canary', 'EUR', 'es-ES')
                """, id, companyId, code, address(), "xlsx-" + id);
    }

    private void insertFamily(UUID id, UUID store, String code, String name) {
        jdbc.update("""
                insert into familia
                  (id, tienda_id, family_id, family_code, nombre, predeterminada)
                values (?, ?, ?, ?, ?, false)
                """, id, store, code, code, name);
    }

    private void insertSubfamily(
            UUID id, UUID family, String suffix, String code, String name) {
        jdbc.update("""
                insert into subfamilia
                  (id, familia_id, subfamily_id, subfamily_suffix,
                   subfamily_code, nombre)
                values (?, ?, ?, ?, ?, ?)
                """, id, family, code, suffix, code, name);
    }

    private static String address() {
        return """
                {"linea1":"Test","ciudad":"Las Palmas","codigoPostal":"35001",
                 "provincia":"Las Palmas","pais":"ES"}
                """;
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

    @TestConfiguration
    static class Configuration {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-31T10:15:30Z"), ZoneOffset.UTC);
        }
    }
}

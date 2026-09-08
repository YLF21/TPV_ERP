package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.CatalogService;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-PostgreSQL transaction proof for the product Excel writer.
 *
 * <p>The catalog facade is mocked only to inject deterministic SQL failure. The writer,
 * datasource, transaction manager, and rollback are real. This test never targets the
 * business database: it requires explicit TPV_ERP_TEST_DB_* credentials and uses a random
 * schema which is dropped after the class.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProductExcelImportApplyWriter.class, com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductExcelImportApplyWriterPostgreSqlTest {

    private static final String URL = environment("TPV_ERP_TEST_DB_URL");
    private static final String USER = environment("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = environment("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "excel_apply_writer_"
            + UUID.randomUUID().toString().replace("-", "");

    static {
        if (!URL.isBlank() && !USER.isBlank() && !PASSWORD.isBlank()) {
            execute("create schema " + SCHEMA);
        }
    }

    @Autowired private ProductExcelImportApplyWriter writer;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CatalogService catalog;
    @MockitoBean private ProductRepository products;
    @MockitoBean private CurrentOrganization organization;

    private UUID storeId;
    private UUID productId;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ProductExcelImportApplyWriterPostgreSqlTest::schemaUrl);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void fixture() {
        jdbc.execute("create table if not exists excel_apply_writer_probe "
                + "(id uuid primary key, purchase numeric not null, attributes text not null)");
        storeId = UUID.randomUUID();
        productId = UUID.randomUUID();
        Store store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        jdbc.update("delete from excel_apply_writer_probe");
        jdbc.update("insert into excel_apply_writer_probe(id, purchase, attributes) values (?, ?, ?)",
                productId, 10, "before");
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getVersion()).thenReturn(3L);
        when(products.findAllByStoreIdAndIdInForUpdate(storeId, List.of(productId)))
                .thenReturn(List.of(product));
    }

    @AfterAll
    void dropSchema() {
        execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void intermediateFailureRollsBackEarlierSqlInTheWholeBatch() {
        doAnswer(invocation -> {
            jdbc.update("update excel_apply_writer_probe set purchase = ?, attributes = ? where id = ?",
                    20, "changed", productId);
            throw new IllegalStateException("forced intermediate failure");
        }).when(catalog).updateProducts(any());

        var item = item(productId, 3L);
        assertThatThrownBy(() -> writer.write(List.of(item)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced intermediate failure");

        Map<String, Object> row = jdbc.queryForMap(
                "select purchase, attributes from excel_apply_writer_probe where id = ?", productId);
        assertThat(row.get("purchase")).isEqualTo(new java.math.BigDecimal("10"));
        assertThat(row.get("attributes")).isEqualTo("before");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void successfulBatchKeepsPurchaseAndAttributeUpdatesInOneWriterTransaction() {
        Product product = products.findAllByStoreIdAndIdInForUpdate(storeId, List.of(productId)).get(0);
        doAnswer(invocation -> {
            jdbc.update("update excel_apply_writer_probe set purchase = ?, attributes = ? where id = ?",
                    25, "after", productId);
            return List.of(product);
        }).when(catalog).updateProducts(any());

        assertThat(writer.write(List.of(item(productId, 3L)))).singleElement()
                .extracting(ProductExcelImportApplyWriter.AppliedProduct::mutated)
                .isEqualTo(true);
        Map<String, Object> row = jdbc.queryForMap(
                "select purchase, attributes from excel_apply_writer_probe where id = ?", productId);
        assertThat(row.get("purchase")).isEqualTo(new java.math.BigDecimal("25"));
        assertThat(row.get("attributes")).isEqualTo("after");
    }

    private ProductExcelImportApplyService.WriteItem item(UUID id, long version) {
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "EXISTING",
                Map.of(), Map.of("id", id.toString()), version,
                Map.of("purchasePrice", Map.of("before", "10", "after", "25")), List.of());
        return new ProductExcelImportApplyService.WriteItem(row,
                new ProductExcelImportApplyService.ExpectedProduct(id, version),
                mock(CatalogService.ProductRequest.class));
    }

    private static String schemaUrl() {
        return URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public";
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    private static void execute(String sql) {
        if (URL.isBlank() || USER.isBlank() || PASSWORD.isBlank()) return;
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("No se pudo preparar PostgreSQL aislado", exception);
        }
    }
}

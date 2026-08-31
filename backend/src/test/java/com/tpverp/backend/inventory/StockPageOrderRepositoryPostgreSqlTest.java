package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.PriceUseMode;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, StockPageOrderRepository.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("postgresql")
class StockPageOrderRepositoryPostgreSqlTest {
    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "tpv_erp_stock_page_order_"
            + UUID.randomUUID().toString().replace("-", "");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired StockPageOrderRepository repository;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?")
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
    void sortsAndContinuesPagesUsingTheSameServerOrder() {
        var context = insertContext();

        var firstPage = repository.findProductIds(
                context.storeId(), null, null, null, null, false,
                null, null, null, context.warehouseId(),
                "salePrice", "asc", null, 2);
        var secondPage = repository.findProductIds(
                context.storeId(), null, null, null, null, false,
                null, null, null, context.warehouseId(),
                "salePrice", "asc", firstPage.getLast(), 2);

        assertThat(firstPage).containsExactly(context.cheapestId(), context.middleId());
        assertThat(secondPage).containsExactly(context.expensiveId());
        assertThat(repository.findProductIds(
                context.storeId(), null, null, null, null, false,
                null, null, null, context.warehouseId(),
                "localStock", "desc", null, 3))
                .containsExactly(context.cheapestId(), context.middleId(), context.expensiveId());
    }

    @Test
    void everyExposedInventoryColumnCanBeSortedAgainstTheRealSchema() {
        var context = insertContext();
        var columns = new String[]{
                "code", "barcode", "name", "type", "discount", "supplier",
                "family", "subfamily", "tax", "taxIncluded", "packageQuantity",
                "purchasePrice", "salePrice", "memberPrice", "wholesalePrice",
                "offerPrice", "offerActive", "offerFrom", "offerUntil",
                "localStock", "totalStock", "stockMin", "stockMax", "status"
        };

        for (var column : columns) {
            assertThatCode(() -> repository.findProductIds(
                    context.storeId(), null, null, null, null, false,
                    null, null, null, context.warehouseId(),
                    column, "asc", null, 10))
                    .as("ordenar por %s", column)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void sortsBeforeTheTerminalWarehouseHasBeenResolved() {
        var context = insertContext();

        assertThatCode(() -> repository.findProductIds(
                context.storeId(), null, null, null, null, false,
                null, null, null, null,
                "name", "asc", null, 10))
                .doesNotThrowAnyException();
    }

    @Test
    void filtersProductsByCalculatedStockStatus() {
        var context = insertContext();

        assertThat(repository.findProductIds(
                context.storeId(), null, null, null, null, false,
                null, null, null, "LOW", null, context.warehouseId(),
                "name", "asc", null, 10))
                .containsExactlyInAnyOrder(context.middleId(), context.expensiveId());
        assertThat(repository.findProductIds(
                context.storeId(), null, null, null, null, false,
                null, null, null, "OK", null, context.warehouseId(),
                "name", "asc", null, 10))
                .containsExactly(context.cheapestId());
    }

    @Test
    void sortsEveryInventoryViewWithItsRealFilterAndNoResolvedWarehouse() {
        var context = insertContext();

        assertThatCode(() -> repository.findProductIds(
                context.storeId(), null, null, null, null, true,
                null, null, null, null,
                "name", "asc", null, 10))
                .as("productos con oferta")
                .doesNotThrowAnyException();
        assertThatCode(() -> repository.findProductIds(
                context.storeId(), null, null, PriceUseMode.MEMBER_PRICE, null, false,
                null, null, null, null,
                "name", "asc", null, 10))
                .as("productos con precio de miembro")
                .doesNotThrowAnyException();
        assertThatCode(() -> repository.findProductIds(
                context.storeId(), null, null, null, DiscountType.NONE, false,
                null, null, null, null,
                "name", "asc", null, 10))
                .as("productos prohibidos a descuento")
                .doesNotThrowAnyException();
    }

    private Context insertContext() {
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var taxId = UUID.randomUUID();
        var familyId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        var cheapestId = UUID.randomUUID();
        var middleId = UUID.randomUUID();
        var expensiveId = UUID.randomUUID();
        jdbc.update("insert into " + SCHEMA
                        + ".empresa (id,tax_id,razon_social,domicilio_fiscal) values (?,'B00000000','Company',cast(? as jsonb))",
                companyId, address());
        jdbc.update("insert into " + SCHEMA
                        + ".tienda (id,empresa_id,codigo_tienda,nombre,direccion,address_normalized_hash,timezone,moneda,locale)"
                        + " values (?,?,'001','Store',cast(? as jsonb),'hash','Europe/Madrid','EUR','es-ES')",
                storeId, companyId, address());
        jdbc.update("insert into " + SCHEMA + ".impuesto_tienda (id,tienda_id,porcentaje) values (?,?,21)",
                taxId, storeId);
        jdbc.update("insert into " + SCHEMA + ".familia (id,tienda_id,nombre) values (?,?,'GENERAL')",
                familyId, storeId);
        jdbc.update("insert into " + SCHEMA
                        + ".almacen (id,tienda_id,nombre,predeterminado) values (?,?,'GENERAL',true)",
                warehouseId, storeId);
        insertProduct(storeId, familyId, taxId, warehouseId, cheapestId, "P002", "Barato", "10.00", 7);
        insertProduct(storeId, familyId, taxId, warehouseId, middleId, "P003", "Medio", "20.00", 4);
        insertProduct(storeId, familyId, taxId, warehouseId, expensiveId, "P001", "Caro", "30.00", 2);
        return new Context(storeId, warehouseId, cheapestId, middleId, expensiveId);
    }

    private void insertProduct(
            UUID storeId,
            UUID familyId,
            UUID taxId,
            UUID warehouseId,
            UUID productId,
            String code,
            String name,
            String salePrice,
            int stock) {
        jdbc.update("insert into " + SCHEMA
                        + ".producto (id,tienda_id,familia_id,impuesto_id,nombre) values (?,?,?,?,?)",
                productId, storeId, familyId, taxId, name);
        jdbc.update("insert into " + SCHEMA
                        + ".producto_identificador (id,tienda_id,producto_id,tipo,valor) values (?,?,?,'CODIGO',?)",
                UUID.randomUUID(), storeId, productId, code);
        jdbc.update("insert into " + SCHEMA
                        + ".producto_precio (id,producto_id,tarifa,importe) values (?,?,'VENTA',?)",
                UUID.randomUUID(), productId, new BigDecimal(salePrice));
        jdbc.update("insert into " + SCHEMA
                        + ".existencia (id,producto_id,almacen_id,cantidad) values (?,?,?,?)",
                UUID.randomUUID(), productId, warehouseId, stock);
    }

    private static String address() {
        return "{\"linea1\":\"Calle Uno\",\"ciudad\":\"Madrid\",\"codigoPostal\":\"28001\",\"provincia\":\"Madrid\",\"pais\":\"ES\"}";
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

    private record Context(
            UUID storeId,
            UUID warehouseId,
            UUID cheapestId,
            UUID middleId,
            UUID expensiveId) {
    }
}

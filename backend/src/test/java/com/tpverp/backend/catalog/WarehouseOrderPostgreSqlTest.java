package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, CatalogService.class, WarehouseOrderPostgreSqlTest.Configuration.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class WarehouseOrderPostgreSqlTest {
    private static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "warehouse_order_" + UUID.randomUUID().toString().replace("-", "");
    private static final UUID STORE = UUID.randomUUID();
    private static final UUID OTHER_STORE = UUID.randomUUID();
    private static final UUID GENERAL = UUID.randomUUID();
    private static final UUID ALPHA = UUID.randomUUID();
    private static final UUID ZETA = UUID.randomUUID();
    private static final UUID FOREIGN = UUID.randomUUID();
    private static final String ADDRESS = """
            {"linea1":"Test","ciudad":"Las Palmas","codigoPostal":"35001",
             "provincia":"Las Palmas","pais":"ES"}
            """;

    static {
        // Upgrade an isolated legacy schema; never use application/store schemas.
        FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                .dataSource(URL, USER, PASSWORD).schemas(SCHEMA).defaultSchema(SCHEMA)
                .createSchemas(true).target("269").load().migrate();
        try (var connection = DriverManager.getConnection(schemaUrl(), USER, PASSWORD)) {
            UUID company = UUID.randomUUID();
            try (var statement = connection.prepareStatement("""
                    insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                    values (?, 'B00000001', 'Orden test', cast(? as jsonb))
                    """)) {
                statement.setObject(1, company);
                statement.setString(2, ADDRESS);
                statement.executeUpdate();
            }
            for (UUID store : List.of(STORE, OTHER_STORE)) {
                try (var statement = connection.prepareStatement("""
                        insert into tienda (id, empresa_id, codigo_tienda, nombre, direccion,
                          address_normalized_hash, timezone, moneda, locale)
                        values (?, ?, ?, 'Orden test', cast(? as jsonb), ?, 'Atlantic/Canary', 'EUR', 'es-ES')
                        """)) {
                    statement.setObject(1, store);
                    statement.setObject(2, company);
                    statement.setString(3, store.equals(STORE) ? "401" : "402");
                    statement.setString(4, ADDRESS);
                    statement.setString(5, "order-" + store);
                    statement.executeUpdate();
                }
            }
            for (UUID id : List.of(GENERAL, ALPHA, ZETA, FOREIGN)) {
                try (var statement = connection.prepareStatement("""
                        insert into almacen (id, tienda_id, nombre, predeterminado, activo, version, direccion, notas)
                        values (?, ?, ?, ?, ?, 7, 'Dirección original', 'Notas originales')
                        """)) {
                    statement.setObject(1, id);
                    statement.setObject(2, id.equals(FOREIGN) ? OTHER_STORE : STORE);
                    statement.setString(3, id.equals(GENERAL) ? "GENERAL" : id.equals(ALPHA) ? "ALFA" : "ZETA");
                    statement.setBoolean(4, id.equals(GENERAL));
                    statement.setBoolean(5, !id.equals(ALPHA));
                    statement.executeUpdate();
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("No se pudo preparar el esquema aislado", failure);
        }
    }

    @Autowired private CatalogService catalog;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentOrganization organization;
    @MockitoBean private AuditService audit;
    @MockitoBean private FamilyProductPageRepository familyPages;
    @MockitoBean private ProductCommercialHistoryService commercialHistory;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", WarehouseOrderPostgreSqlTest::schemaUrl);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void upgradesPreservesDataAndRejectsConcurrentStaleOrForeignChangesAtomically() throws Exception {
        var store = mock(Store.class);
        when(store.getId()).thenReturn(STORE);
        when(store.getDireccion()).thenReturn(Map.of("linea1", "Test", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES"));
        when(organization.currentStore()).thenReturn(store);
        var controller = new WarehouseController(catalog);
        var initial = controller.list();
        assertThat(initial).extracting(WarehouseController.WarehouseView::id).containsExactly(GENERAL, ALPHA, ZETA);
        assertThat(initial).extracting(WarehouseController.WarehouseView::displayOrder).containsExactly(0, 1, 2);
        assertThat(initial).extracting(WarehouseController.WarehouseView::version).containsOnly(7L);
        assertThat(initial.get(1).active()).isFalse();
        var operational = jdbc.queryForList("select id, activo, predeterminado, direccion, notas from almacen order by id");
        var reversed = List.of(item(ZETA, 7), item(ALPHA, 7), item(GENERAL, 7));

        // A concurrent request must wait for the same store row used by create/delete.
        try (var blocker = DriverManager.getConnection(schemaUrl(), USER, PASSWORD);
                var lock = blocker.prepareStatement("select id from tienda where id = ? for update");
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            lock.setObject(1, STORE);
            lock.executeQuery().close();
            var move = executor.submit(() -> catalog.reorderWarehouses(reversed));
            assertThatThrownBy(() -> move.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            blocker.commit();
            assertThat(move.get(10, TimeUnit.SECONDS)).extracting(Warehouse::getId).containsExactly(ZETA, ALPHA, GENERAL);
        }
        assertThat(controller.list()).extracting(WarehouseController.WarehouseView::id).containsExactly(ZETA, ALPHA, GENERAL);
        assertThat(controller.list()).extracting(WarehouseController.WarehouseView::version).containsExactly(8L, 7L, 8L);
        var saved = jdbc.queryForList("select * from almacen order by id");
        assertThatThrownBy(() -> catalog.reorderWarehouses(List.of(item(GENERAL, 7), item(ALPHA, 7), item(ZETA, 7))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> catalog.reorderWarehouses(List.of(item(GENERAL, 8), item(ALPHA, 7), item(FOREIGN, 7))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.reorderWarehouses(List.of(item(GENERAL, 8), item(ZETA, 8))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForList("select * from almacen order by id")).isEqualTo(saved);

        var first = List.of(item(ALPHA, 7), item(GENERAL, 8), item(ZETA, 8));
        var second = List.of(item(GENERAL, 8), item(ZETA, 8), item(ALPHA, 7));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> attempt(start, first));
            var b = executor.submit(() -> attempt(start, second));
            start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForList("select id, activo, predeterminado, direccion, notas from almacen order by id"))
                .isEqualTo(operational);
        // Concurrent creations cannot allocate the same tail position.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> catalog.createWarehouse("NUEVO A"));
            var b = executor.submit(() -> catalog.createWarehouse("NUEVO B"));
            assertThat(List.of(a.get(10, TimeUnit.SECONDS).getDisplayOrder(), b.get(10, TimeUnit.SECONDS).getDisplayOrder()))
                    .containsExactlyInAnyOrder(3, 4);
        }
        assertThat(controller.list()).extracting(WarehouseController.WarehouseView::displayOrder).containsExactly(0, 1, 2, 3, 4);
        assertThat(jdbc.queryForObject("select display_order from almacen where id = ?", Integer.class, FOREIGN)).isZero();
    }

    private boolean attempt(CountDownLatch start, List<WarehouseController.OrderItem> request) throws Exception {
        start.await();
        try {
            catalog.reorderWarehouses(request);
            return true;
        } catch (IllegalStateException conflict) {
            return false;
        }
    }

    private static WarehouseController.OrderItem item(UUID id, long version) {
        return new WarehouseController.OrderItem(id, version);
    }

    private static String schemaUrl() {
        return URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public";
    }

    @TestConfiguration
    static class Configuration {
        @Bean @Primary Clock clock() { return Clock.systemUTC(); }
    }
}

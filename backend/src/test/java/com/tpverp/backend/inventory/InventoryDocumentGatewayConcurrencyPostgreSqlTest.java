package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
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
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(InventoryDocumentGatewayConcurrencyPostgreSqlTest.Configuration.class)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("integration")
class InventoryDocumentGatewayConcurrencyPostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA =
            "tpv_erp_inventory_" + UUID.randomUUID().toString().replace("-", "");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private CommercialDocumentRepository documents;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private StockLevelRepository stockLevels;
    @Autowired private StockMovementRepository movements;
    @Autowired private ProductRepository products;
    @MockitoBean private CurrentOrganization organization;
    @MockitoBean private StockMovementSyncPublisher syncPublisher;

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
    void concurrentTicketConfirmationsPreserveStockAndMovementLedger() throws Exception {
        var context = insertContext();
        var company = mock(Company.class);
        when(company.getId()).thenReturn(context.companyId());
        when(organization.currentCompany()).thenReturn(company);

        for (int round = 0; round < 20; round++) {
            var fixture = insertRound(context, round);
            confirmTogether(
                    gatewayWithUnprotectedReadBarrier(new CountDownLatch(2)),
                    context.storeId(),
                    fixture.firstDocumentId(),
                    fixture.secondDocumentId());

            BigDecimal stock = jdbc.queryForObject(
                    "select cantidad from " + SCHEMA
                            + ".existencia where producto_id = ? and almacen_id = ?",
                    BigDecimal.class,
                    fixture.productId(),
                    context.warehouseId());
            BigDecimal movementDelta = jdbc.queryForObject(
                    "select coalesce(sum(cantidad), 0) from " + SCHEMA
                            + ".movimiento_stock where producto_id = ? and almacen_id = ?",
                    BigDecimal.class,
                    fixture.productId(),
                    context.warehouseId());
            Integer movementCount = jdbc.queryForObject(
                    "select count(*) from " + SCHEMA
                            + ".movimiento_stock where producto_id = ? and almacen_id = ?",
                    Integer.class,
                    fixture.productId(),
                    context.warehouseId());

            assertThat(movementCount).isEqualTo(2);
            assertThat(movementDelta).isEqualByComparingTo("-2");
            assertThat(stock).isEqualByComparingTo("8");
            assertThat(stock).isEqualByComparingTo(new BigDecimal("10").add(movementDelta));
        }
    }

    private void confirmTogether(
            InventoryDocumentGateway gateway,
            UUID storeId,
            UUID firstDocumentId,
            UUID secondDocumentId)
            throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(confirm(gateway, firstDocumentId, storeId, ready, start));
            var second = pool.submit(confirm(gateway, secondDocumentId, storeId, ready, start));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Callable<Boolean> confirm(
            InventoryDocumentGateway gateway,
            UUID documentId, UUID storeId, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return transactions.execute(status -> gateway.confirm(
                    documents.findByIdAndTiendaId(documentId, storeId).orElseThrow()));
        };
    }

    private InventoryDocumentGateway gatewayWithUnprotectedReadBarrier(
            CountDownLatch unprotectedReads) {
        var instrumentedStockLevels = (StockLevelRepository) Proxy.newProxyInstance(
                StockLevelRepository.class.getClassLoader(),
                new Class<?>[] {StockLevelRepository.class},
                (proxy, method, arguments) -> {
                    var result = invokeStockLevelRepository(method, arguments);
                    if (method.getName().equals("findByProductIdAndWarehouseId")) {
                        unprotectedReads.countDown();
                        assertThat(unprotectedReads.await(10, TimeUnit.SECONDS))
                                .as("both transactions must read the unlocked stock row before either updates it")
                                .isTrue();
                    }
                    return result;
                });
        return new InventoryDocumentGateway(
                instrumentedStockLevels, movements, products, organization, syncPublisher,
                Clock.fixed(Instant.parse("2026-06-12T12:00:00Z"), ZoneOffset.UTC));
    }

    private Object invokeStockLevelRepository(java.lang.reflect.Method method, Object[] arguments)
            throws Throwable {
        try {
            return method.invoke(stockLevels, arguments);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private Context insertContext() {
        var context = new Context(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("""
                insert into %s.empresa (id, tax_id, razon_social, domicilio_fiscal)
                values (?, 'B00000000', 'Company', cast(? as jsonb))
                """.formatted(SCHEMA), context.companyId(), address());
        jdbc.update("""
                insert into %s.tienda (
                    id, empresa_id, codigo_tienda, nombre, direccion, address_normalized_hash,
                    timezone, moneda, locale)
                values (?, ?, '001', 'Store', cast(? as jsonb), 'hash',
                    'Atlantic/Canary', 'EUR', 'es-ES')
                """.formatted(SCHEMA), context.storeId(), context.companyId(), address());
        jdbc.update("""
                insert into %s.rol (id, tienda_id, nombre, protegido)
                values (?, ?, 'ADMIN', true)
                """.formatted(SCHEMA), context.roleId(), context.storeId());
        jdbc.update("""
                insert into %s.usuario (id, tienda_id, nombre, user_name, password_hash, rol_id, protegido)
                values (?, ?, 'ADMIN', 'ADMIN', 'hash', ?, true)
                """.formatted(SCHEMA), context.userId(), context.storeId(), context.roleId());
        jdbc.update("""
                insert into %s.impuesto_tienda (id, tienda_id, porcentaje)
                values (?, ?, 21)
                """.formatted(SCHEMA), context.taxId(), context.storeId());
        jdbc.update("""
                insert into %s.familia (id, tienda_id, nombre)
                values (?, ?, 'GENERAL')
                """.formatted(SCHEMA), context.familyId(), context.storeId());
        jdbc.update("""
                insert into %s.almacen (id, tienda_id, nombre, predeterminado)
                values (?, ?, 'GENERAL', true)
                """.formatted(SCHEMA), context.warehouseId(), context.storeId());
        return context;
    }

    private Round insertRound(Context context, int round) {
        var productId = UUID.randomUUID();
        var firstDocumentId = UUID.randomUUID();
        var secondDocumentId = UUID.randomUUID();
        jdbc.update("""
                insert into %s.producto (id, tienda_id, familia_id, impuesto_id, nombre)
                values (?, ?, ?, ?, ?)
                """.formatted(SCHEMA), productId, context.storeId(), context.familyId(),
                context.taxId(), "Producto " + round);
        jdbc.update("""
                insert into %s.existencia (id, producto_id, almacen_id, cantidad)
                values (?, ?, ?, 10)
                """.formatted(SCHEMA), UUID.randomUUID(), productId, context.warehouseId());
        insertTicket(context, firstDocumentId, productId, round * 2 + 1);
        insertTicket(context, secondDocumentId, productId, round * 2 + 2);
        return new Round(productId, firstDocumentId, secondDocumentId);
    }

    private void insertTicket(Context context, UUID documentId, UUID productId, int position) {
        jdbc.update("""
                insert into %s.documento (
                    id, tienda_id, almacen_id, tipo, estado, fecha, creado_en,
                    creado_por, descuento_global, base_total, impuesto_total, total, moneda, origen_stock)
                values (?, ?, ?, 'TICKET', 'BORRADOR', current_date, now(), ?,
                    0, 10, 2.10, 12.10, 'EUR', false)
                """.formatted(SCHEMA), documentId, context.storeId(), context.warehouseId(), context.userId());
        jdbc.update("""
                insert into %s.documento_linea (
                    id, documento_id, producto_id, posicion, cantidad, codigo,
                    nombre, tarifa, precio_unitario, descuento, impuestos_incluidos,
                    regimen_impuesto, porcentaje_impuesto, base, impuesto, total)
                values (?, ?, ?, 1, 1, ?, 'Producto', 'VENTA', 12.10, 0, true,
                    'IVA', 21, 10, 2.10, 12.10)
                """.formatted(SCHEMA), UUID.randomUUID(), documentId, productId, "P-" + position);
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
        @Primary
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-06-12T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    private record Context(
            UUID companyId,
            UUID storeId,
            UUID roleId,
            UUID userId,
            UUID taxId,
            UUID familyId,
            UUID warehouseId) {
    }

    private record Round(UUID productId, UUID firstDocumentId, UUID secondDocumentId) {
    }
}

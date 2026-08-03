package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StockCountService.class, StockCountPostgreSqlTest.Configuration.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("postgresql")
class StockCountPostgreSqlTest {
    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "tpv_erp_stock_count_" + UUID.randomUUID().toString().replace("-", "");

    static { execute("create schema " + SCHEMA); }

    @Autowired StockCountService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CurrentOrganization organization;
    @MockitoBean StockMovementSyncPublisher syncPublisher;

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

    @AfterAll static void dropSchema() { execute("drop schema if exists " + SCHEMA + " cascade"); }

    @Test
    void completeCountFlowIsAtomicAndConcurrentConfirmationIsIdempotent() throws Exception {
        var context = insertContext();
        var store = mock(Store.class);
        var company = mock(Company.class);
        var user = mock(UserAccount.class);
        when(store.getId()).thenReturn(context.storeId());
        when(company.getId()).thenReturn(context.companyId());
        when(user.getId()).thenReturn(context.userId());
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        var authentication = new UsernamePasswordAuthenticationToken("ADMIN", "token");
        when(organization.currentUser(authentication)).thenReturn(user);

        var draft = service.create(context.warehouseId(), "Cierre mensual", authentication);
        var counted = service.upsertLine(draft.id(), context.productId(), new BigDecimal("12"));
        assertThat(counted.lines()).singleElement().satisfies(line -> {
            assertThat(line.expectedQuantity()).isEqualByComparingTo("10.000");
            assertThat(line.difference()).isEqualByComparingTo("2.000");
        });

        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return service.confirm(draft.id(), authentication); });
            var second = executor.submit(() -> { start.await(); return service.confirm(draft.id(), authentication); });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).status()).isEqualTo(StockCountStatus.CONFIRMED);
            assertThat(second.get(20, TimeUnit.SECONDS).status()).isEqualTo(StockCountStatus.CONFIRMED);
        }

        assertThat(jdbc.queryForObject("select cantidad from " + SCHEMA
                + ".existencia where producto_id=? and almacen_id=?", BigDecimal.class,
                context.productId(), context.warehouseId())).isEqualByComparingTo("12.000");
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA
                + ".movimiento_stock where recuento_stock_id=?", Integer.class, draft.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select sum(cantidad) from " + SCHEMA
                + ".movimiento_stock where producto_id=? and almacen_id=?", BigDecimal.class,
                context.productId(), context.warehouseId())).isEqualByComparingTo("12.000");
        var detail = service.get(draft.id());
        assertThat(detail.lines()).singleElement().satisfies(line ->
                assertThat(line.appliedDifference()).isEqualByComparingTo("2.000"));
    }

    private Context insertContext() {
        var value = new Context(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("insert into " + SCHEMA + ".empresa (id,tax_id,razon_social,domicilio_fiscal) values (?,'B00000000','Company',cast(? as jsonb))",
                value.companyId(), address());
        jdbc.update("insert into " + SCHEMA + ".tienda (id,empresa_id,codigo_tienda,nombre,direccion,address_normalized_hash,timezone,moneda,locale) values (?,?,'001','Store',cast(? as jsonb),'hash','Europe/Madrid','EUR','es-ES')",
                value.storeId(), value.companyId(), address());
        jdbc.update("insert into " + SCHEMA + ".rol (id,tienda_id,nombre,protegido) values (?,?,'ADMIN',true)", value.roleId(), value.storeId());
        jdbc.update("insert into " + SCHEMA + ".usuario (id,tienda_id,nombre,user_name,password_hash,rol_id,protegido) values (?,?,'ADMIN','ADMIN','hash',?,true)",
                value.userId(), value.storeId(), value.roleId());
        jdbc.update("insert into " + SCHEMA + ".impuesto_tienda (id,tienda_id,porcentaje) values (?,?,21)", value.taxId(), value.storeId());
        jdbc.update("insert into " + SCHEMA + ".familia (id,tienda_id,nombre) values (?,?,'GENERAL')", value.familyId(), value.storeId());
        jdbc.update("insert into " + SCHEMA + ".almacen (id,tienda_id,nombre,predeterminado) values (?,?,'GENERAL',true)", value.warehouseId(), value.storeId());
        jdbc.update("insert into " + SCHEMA + ".producto (id,tienda_id,familia_id,impuesto_id,nombre) values (?,?,?,?,'Producto')",
                value.productId(), value.storeId(), value.familyId(), value.taxId());
        jdbc.update("insert into " + SCHEMA + ".existencia (id,producto_id,almacen_id,cantidad) values (?,?,?,10)",
                UUID.randomUUID(), value.productId(), value.warehouseId());
        jdbc.update("insert into " + SCHEMA + ".movimiento_stock (id,producto_id,almacen_id,usuario_id,tipo,cantidad,motivo,creado_en) values (?,?,?,?, 'AJUSTE',10,'APERTURA',now())",
                UUID.randomUUID(), value.productId(), value.warehouseId(), value.userId());
        return value;
    }

    private static String address() { return "{\"linea1\":\"Calle Uno\",\"ciudad\":\"Madrid\",\"codigoPostal\":\"28001\",\"provincia\":\"Madrid\",\"pais\":\"ES\"}"; }
    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " no configurada");
        return value;
    }
    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) { throw new IllegalStateException("No se pudo preparar PostgreSQL", exception); }
    }
    @TestConfiguration static class Configuration {
        @Bean @Primary Clock clock() { return Clock.fixed(Instant.parse("2026-08-03T10:00:00Z"), ZoneOffset.UTC); }
    }
    private record Context(UUID companyId, UUID storeId, UUID roleId, UUID userId, UUID taxId,
                           UUID familyId, UUID warehouseId, UUID productId) {}
}

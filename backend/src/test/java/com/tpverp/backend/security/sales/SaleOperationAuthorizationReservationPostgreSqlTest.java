package com.tpverp.backend.security.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.sql.DriverManager;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class,
    SaleOperationAuthorizationAttemptService.class,
    SaleOperationAuthorizationReservationPostgreSqlTest.Configuration.class
})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("integration")
class SaleOperationAuthorizationReservationPostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA =
            "sale_auth_reservation_"
                    + UUID.randomUUID().toString().replace("-", "");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private SaleOperationAuthorizationAttemptService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private AuditService audit;

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
    void simultaneousRequestsReserveOnlyOnePasswordVerification() throws Exception {
        var context = insertContext();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> reserve(context, ready, start));
            var second = pool.submit(() -> reserve(context, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(java.util.List.of(
                            first.get(15, TimeUnit.SECONDS),
                            second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("RESERVED", "THROTTLED");
        }

        assertThat(jdbc.queryForObject("""
                select count(*)
                from intento_autorizacion_operacion_venta
                where tienda_id = ?
                  and operador_id = ?
                  and terminal_id = ?
                  and codigo_operacion = ?
                  and reserva_id is not null
                  and reserva_hasta is not null
                """, Integer.class,
                context.storeId(),
                context.operatorId(),
                context.terminalId(),
                context.operationCode().name())).isEqualTo(1);
    }

    private String reserve(
            SaleOperationAuthorizationAttemptService.Context context,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            service.reserve(context, "SUPERVISOR");
            return "RESERVED";
        } catch (SaleOperationAuthorizationThrottledException exception) {
            return "THROTTLED";
        }
    }

    private SaleOperationAuthorizationAttemptService.Context insertContext() {
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var terminalId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var address = "{\"linea1\":\"x\",\"ciudad\":\"x\","
                + "\"codigoPostal\":\"1\",\"provincia\":\"x\",\"pais\":\"ES\"}";
        jdbc.update("""
                insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                values (?, 'B00000000', 'Company', cast(? as jsonb))
                """, companyId, address);
        jdbc.update("""
                insert into tienda (
                    id, empresa_id, codigo_tienda, nombre, direccion,
                    address_normalized_hash, timezone, moneda, locale)
                values (?, ?, '001', 'Store', cast(? as jsonb), 'hash',
                    'Atlantic/Canary', 'EUR', 'es-ES')
                """, storeId, companyId, address);
        jdbc.update("""
                insert into terminal (id, tienda_id, nombre, tipo, credential_hash)
                values (?, ?, 'TPV', 'TERMINAL_VENTA', 'hash')
                """, terminalId, storeId);
        jdbc.update("""
                insert into rol (id, tienda_id, nombre)
                values (?, ?, 'SELLER')
                """, roleId, storeId);
        jdbc.update("""
                insert into usuario (
                    id, tienda_id, nombre, user_name, password_hash, rol_id)
                values (?, ?, 'SELLER', 'SELLER', 'hash', ?)
                """, userId, storeId, roleId);
        return new SaleOperationAuthorizationAttemptService.Context(
                storeId,
                userId,
                "SELLER",
                terminalId,
                SaleOperationCode.CANCEL_TICKET);
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration
    static class Configuration {

        @Bean
        Clock clock() {
            return Clock.fixed(
                    Instant.parse("2026-07-31T12:00:00Z"),
                    ZoneOffset.UTC);
        }
    }
}

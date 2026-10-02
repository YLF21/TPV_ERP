package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class CashTimelineRepositoryPostgreSqlTest {
    @Test
    void preservesEventStateAfterClosingAndScopesTheTimelineToItsStoreAndTerminal() throws Exception {
        var url = System.getenv("TPV_TEST_DB_URL");
        var username = System.getenv("TPV_TEST_DB_USERNAME");
        var password = System.getenv("TPV_TEST_DB_PASSWORD");
        assumeTrue(url != null && username != null && password != null, "Configure TPV_TEST_DB_*");
        var schema = "cash_timeline_" + UUID.randomUUID().toString().replace("-", "");
        try {
            FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                    .dataSource(url, username, password).schemas(schema).defaultSchema(schema)
                    .createSchemas(true).target("266").load().migrate();
            try (var connection = DriverManager.getConnection(url, username, password)) {
                execute(connection, "set search_path to " + schema);
                var store = UUID.randomUUID();
                var terminal = UUID.randomUUID();
                var user = UUID.randomUUID();
                var session = UUID.randomUUID();
                seedStore(connection, store, terminal, user);
                execute(connection, """
                        insert into sesion_caja(id,tienda_id,terminal_id,usuario_apertura_id,abierta_en,
                                               fondo_inicial,estado)
                        values ('%s','%s','%s','%s','2026-08-01T08:00:00Z',50,'ABIERTA')
                        """.formatted(session, store, terminal, user));
                movement(connection, store, terminal, user, session, "ENTRADA", "10", "09:00:00");
                movement(connection, store, terminal, user, session, "COBRO_EFECTIVO", "25", "10:00:00");
                execute(connection, """
                        insert into movimiento_caja(id,tienda_id,terminal_id,tipo,importe,creado_en,usuario_id)
                        select gen_random_uuid(),'%s','%s','ENTRADA_ENTRE_SESIONES',1,
                            '2026-07-31T12:00:00Z'::timestamptz + n * interval '1 second','%s'
                        from generate_series(1,1001) as n
                        """.formatted(store, terminal, user));
                FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                        .dataSource(url, username, password).schemas(schema).defaultSchema(schema).load().migrate();
                assertThat(scalar(connection, """
                        select count(*) from caja_actividad_referencia
                        where tienda_id='%s' and fecha_local='2026-07-31' and referencia is null
                        """.formatted(store))).isEqualTo("2");
                var repository = new CashTimelineRepository(new NamedParameterJdbcTemplate(
                        new SingleConnectionDataSource(connection, true)));
                var from = Instant.parse("2026-08-01T00:00:00Z");
                var to = Instant.parse("2026-08-02T00:00:00Z");
                var beforeClosing = repository.find(store, terminal, from, to);
                assertThat(beforeClosing).extracting(CashTimelineView.Item::cashState)
                        .containsExactly(CashSessionStatus.ABIERTA, CashSessionStatus.ABIERTA);
                assertThat(beforeClosing).extracting(CashTimelineView.Item::reference)
                        .containsExactly("260801001", "260801002");
                assertThat(beforeClosing).allSatisfy(item -> assertThat(item.sourceReference()).isNotBlank());

                // A final withdrawal belongs to the open session even when its timestamp equals the closing timestamp.
                movement(connection, store, terminal, user, session, "RETIRADA_CIERRE", "20", "18:00:00");
                execute(connection, """
                        update sesion_caja set estado='CERRADA',usuario_cierre_id='%s',
                            cerrada_en='2026-08-01T18:00:00Z',efectivo_teorico=65,fondo_dejado=65,descuadre=0
                        where id='%s'
                        """.formatted(user, session));
                movement(connection, store, terminal, user, null, "ENTRADA_ENTRE_SESIONES", "5", "19:00:00");
                var afterClosing = repository.find(store, terminal, from, to);
                assertThat(afterClosing.subList(0, 2)).containsExactlyElementsOf(beforeClosing);
                assertThat(afterClosing).extracting(CashTimelineView.Item::action)
                        .containsExactly("OPENING", "ENTRADA", "RETIRADA_CIERRE", "CLOSING", "ENTRADA_ENTRE_SESIONES");
                assertThat(afterClosing).extracting(CashTimelineView.Item::cashState).containsExactly(
                        CashSessionStatus.ABIERTA, CashSessionStatus.ABIERTA, CashSessionStatus.ABIERTA,
                        CashSessionStatus.CERRADA, CashSessionStatus.CERRADA);
                assertThat(afterClosing).extracting(item -> item.balance().intValueExact())
                        .containsExactly(50, 60, 65, 65, 70);
                assertThat(afterClosing).extracting(CashTimelineView.Item::reference)
                        .containsExactly("260801001", "260801002", "260801003", "260801004", "260801005");
                assertThat(repository.find(store, terminal, Instant.parse("2026-08-01T18:00:01Z"), to))
                        .containsExactly(afterClosing.get(4));
                assertThat(repository.find(UUID.randomUUID(), terminal, from, to)).isEmpty();
                assertThat(repository.find(store, UUID.randomUUID(), from, to)).isEmpty();

                // Replaying an unrelated session update never renumbers its earlier events.
                execute(connection, "update sesion_caja set estado=estado where id='%s'".formatted(session));
                assertThat(repository.find(store, terminal, from, to)).containsExactlyElementsOf(afterClosing);

                verifiesAtomicCloseLimitAndReceiptsExemption(connection, store, terminal, user);
                verifiesJpaCommitLimitProducesBusinessConflict(url, username, password, schema, store, terminal, user);
                verifiesConcurrentTerminalsShareTheStoreCounter(url, username, password, schema, connection, store, terminal, user);
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, username, password)) {
                execute(connection, "drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static void verifiesJpaCommitLimitProducesBusinessConflict(String url, String username, String password,
            String schema, UUID store, UUID terminal, UUID user) throws Exception {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, username, password);
        var factory = new org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setManagedTypes(org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes.of(
                CashMovement.class.getName(), CashMovementDenomination.class.getName()));
        factory.setJpaVendorAdapter(new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(java.util.Map.of("hibernate.hbm2ddl.auto", "none"));
        factory.afterPropertiesSet();
        try {
            var entityManagerFactory = factory.getObject();
            var transactionManager = new org.springframework.orm.jpa.JpaTransactionManager(entityManagerFactory);
            var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            var failure = org.assertj.core.api.Assertions.catchThrowable(() -> transaction.executeWithoutResult(status -> {
                var entityManager = org.springframework.orm.jpa.EntityManagerFactoryUtils
                        .getTransactionalEntityManager(entityManagerFactory);
                entityManager.persist(CashMovement.betweenSessionEntry(store, terminal, java.math.BigDecimal.ONE,
                        Instant.parse("2026-08-01T22:00:00Z"), user, user, "Limit test"));
                // Leave the INSERT to the real JPA commit/flush path, as a save() operation does.
            }));
            assertThat(failure).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            var handler = new com.tpverp.backend.shared.api.ApiExceptionHandler(
                    new org.springframework.context.support.StaticMessageSource());
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                    .standaloneSetup(new LimitFailureController((RuntimeException) failure))
                    .setControllerAdvice(handler).build();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/cash-test/limit"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.code").value("CASH_ACTIVITY_DAILY_LIMIT"));
        } finally {
            factory.destroy();
        }
    }

    @org.springframework.web.bind.annotation.RestController
    static class LimitFailureController {
        private final RuntimeException failure;

        LimitFailureController(RuntimeException failure) {
            this.failure = failure;
        }

        @org.springframework.web.bind.annotation.GetMapping("/cash-test/limit")
        void fail() {
            throw failure;
        }
    }

    private static void verifiesAtomicCloseLimitAndReceiptsExemption(Connection connection, UUID store,
            UUID terminal, UUID user) throws Exception {
        var session = UUID.randomUUID();
        execute(connection, """
                insert into sesion_caja(id,tienda_id,terminal_id,usuario_apertura_id,abierta_en,fondo_inicial,estado)
                values ('%s','%s','%s','%s','2026-08-01T20:00:00Z',100,'ABIERTA')
                """.formatted(session, store, terminal, user));
        execute(connection, "update caja_actividad_contador set ultimo_numero=998 where tienda_id='%s' and fecha_local='2026-08-01'".formatted(store));
        connection.setAutoCommit(false);
        try {
            movement(connection, store, terminal, user, session, "RETIRADA_CIERRE", "20", "21:00:00");
            assertThatThrownBy(() -> execute(connection, """
                    update sesion_caja set estado='CERRADA',cerrada_en='2026-08-01T21:00:00Z',
                        usuario_cierre_id='%s',efectivo_teorico=80,fondo_dejado=80,descuadre=0 where id='%s'
                    """.formatted(user, session)))
                    .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("cash_activity_daily_limit");
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
        assertThat(scalar(connection, "select estado from sesion_caja where id='%s'".formatted(session))).isEqualTo("ABIERTA");
        assertThat(scalar(connection, "select count(*) from movimiento_caja where sesion_caja_id='%s'".formatted(session)))
                .isEqualTo("0");
        assertThat(scalar(connection, "select ultimo_numero from caja_actividad_contador where tienda_id='%s' and fecha_local='2026-08-01'".formatted(store)))
                .isEqualTo("998");
        movement(connection, store, terminal, user, session, "ENTRADA", "1", "21:10:00");
        movement(connection, store, terminal, user, session, "COBRO_EFECTIVO", "2", "21:11:00");
        assertThatThrownBy(() -> movement(connection, store, terminal, user, session, "ENTRADA", "1", "21:12:00"))
                .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("cash_activity_daily_limit");
        assertThat(scalar(connection, "select count(*) from movimiento_caja where sesion_caja_id='%s'".formatted(session)))
                .isEqualTo("2");
    }

    private static void verifiesConcurrentTerminalsShareTheStoreCounter(String url, String username, String password,
            String schema, Connection connection, UUID store, UUID terminal, UUID user) throws Exception {
        var secondTerminal = UUID.randomUUID();
        execute(connection, """
                insert into terminal(id,tienda_id,nombre,tipo,credential_hash,activa,aprobada)
                values ('%s','%s','TPV 2','TERMINAL_VENTA','h',true,true)
                """.formatted(secondTerminal, store));
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks = java.util.List.of(terminal, secondTerminal).stream().map(target -> executor.submit(() -> {
                try (var concurrent = DriverManager.getConnection(url, username, password)) {
                    execute(concurrent, "set search_path to " + schema);
                    ready.countDown();
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                    // At this UTC instant the store date is already August 2 (Canary summer time).
                    execute(concurrent, """
                            insert into movimiento_caja(id,tienda_id,terminal_id,tipo,importe,creado_en,usuario_id)
                            values ('%s','%s','%s','ENTRADA_ENTRE_SESIONES',1,'2026-08-01T23:30:00Z','%s')
                            """.formatted(UUID.randomUUID(), store, target, user));
                    return true;
                }
            })).toList();
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var task : tasks) assertThat(task.get(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(scalar(connection, """
                select string_agg(referencia,',' order by referencia) from caja_actividad_referencia
                where tienda_id='%s' and fecha_local='2026-08-02'
                """.formatted(store))).isEqualTo("260802001,260802002");
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static void seedStore(Connection connection, UUID store, UUID terminal, UUID user) throws Exception {
        var company = UUID.randomUUID();
        var role = UUID.randomUUID();
        execute(connection, """
                insert into empresa(id,tax_id,razon_social,domicilio_fiscal)
                values ('%s','B1','Test','{"linea1":"x","ciudad":"x","codigoPostal":"1","provincia":"x","pais":"ES"}')
                """.formatted(company));
        execute(connection, """
                insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda)
                values ('%s','%s','Test','{"linea1":"x","ciudad":"x","codigoPostal":"1","provincia":"x","pais":"ES"}',
                        'h','Atlantic/Canary','EUR','es-ES','001')
                """.formatted(store, company));
        execute(connection, """
                insert into terminal(id,tienda_id,nombre,tipo,credential_hash,activa,aprobada)
                values ('%s','%s','TPV','TERMINAL_VENTA','h',true,true)
                """.formatted(terminal, store));
        execute(connection, "insert into rol(id,tienda_id,nombre) values ('%s','%s','SELLER')".formatted(role, store));
        execute(connection, """
                insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id)
                values ('%s','%s','CAJERO','cajero','h','%s')
                """.formatted(user, store, role));
    }

    private static void movement(Connection connection, UUID store, UUID terminal, UUID user, UUID session,
            String type, String amount, String time) throws Exception {
        execute(connection, """
                insert into movimiento_caja(id,tienda_id,terminal_id,sesion_caja_id,tipo,importe,creado_en,usuario_id)
                values ('%s','%s','%s',%s,'%s',%s,'2026-08-01T%sZ','%s')
                """.formatted(UUID.randomUUID(), store, terminal, session == null ? "null" : "'" + session + "'",
                        type, amount, time, user));
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}

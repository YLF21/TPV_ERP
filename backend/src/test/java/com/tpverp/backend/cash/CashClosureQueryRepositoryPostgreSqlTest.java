package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class CashClosureQueryRepositoryPostgreSqlTest {

    @Test
    void filtersAndContinuesClosuresInTerminalThenNewestOrder() throws Exception {
        var database = DatabaseEnvironment.resolve();
        assumeTrue(database != null, "Configure TPV_TEST_DB_* o TPV_ERP_TEST_DB_* para PostgreSQL");
        var schema = "cash_closure_query_" + UUID.randomUUID().toString().replace("-", "");
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var userOneId = UUID.randomUUID();
        var userTwoId = UUID.randomUUID();
        var terminalAId = UUID.randomUUID();
        var terminalBId = UUID.randomUUID();
        var newestAId = UUID.randomUUID();
        var olderAId = UUID.randomUUID();
        var terminalBClosureId = UUID.randomUUID();
        try {
            FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                    .dataSource(database.url(), database.user(), database.password())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .load()
                    .migrate();
            try (var connection = DriverManager.getConnection(database.url(), database.user(), database.password())) {
                try (var statement = connection.createStatement()) {
                    statement.execute("set search_path to " + schema);
                    statement.execute("""
                            insert into empresa(id,tax_id,razon_social,domicilio_fiscal)
                            values ('%s','B1','Test','{"linea1":"x","ciudad":"x","codigoPostal":"1","provincia":"x","pais":"ES"}')
                            """.formatted(companyId));
                    statement.execute("""
                            insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda)
                            values ('%s','%s','T','{"linea1":"x","ciudad":"x","codigoPostal":"1","provincia":"x","pais":"ES"}',
                                    'h','Atlantic/Canary','EUR','es-ES','001')
                            """.formatted(storeId, companyId));
                    statement.execute("""
                            insert into terminal(id,tienda_id,nombre,tipo,credential_hash)
                            values ('%s','%s','TERMINAL B','TERMINAL_VENTA','h'),
                                   ('%s','%s','TERMINAL A','TERMINAL_VENTA','h')
                            """.formatted(terminalBId, storeId, terminalAId, storeId));
                    statement.execute("insert into rol(id,tienda_id,nombre) values ('%s','%s','SELLER')"
                            .formatted(roleId, storeId));
                    statement.execute("""
                            insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id)
                            values ('%s','%s','USER ONE','user-one','h','%s'),
                                   ('%s','%s','USER TWO','user-two','h','%s')
                            """.formatted(userOneId, storeId, roleId, userTwoId, storeId, roleId));
                    insertClosure(statement, newestAId, storeId, terminalAId, userOneId,
                            "2026-07-31T18:00:00Z", "120.00", "20.00", "-1.00");
                    insertClosure(statement, olderAId, storeId, terminalAId, userTwoId,
                            "2026-07-31T17:00:00Z", "100.00", "10.00", "0.00");
                    insertClosure(statement, terminalBClosureId, storeId, terminalBId, userOneId,
                            "2026-07-31T19:00:00Z", "80.00", "8.00", "2.00");
                }
                var repository = new CashClosureQueryRepository(new NamedParameterJdbcTemplate(
                        new SingleConnectionDataSource(connection, true)));
                var firstPage = repository.findClosures(
                        storeId, Instant.parse("2026-07-31T00:00:00Z"),
                        Instant.parse("2026-08-01T00:00:00Z"), null, null,
                        false, null, 2);
                assertThat(firstPage).extracting(CashClosureQueryRepository.CashClosureRow::id)
                        .containsExactly(newestAId, olderAId);

                var secondPage = repository.findClosures(
                        storeId, Instant.parse("2026-07-31T00:00:00Z"),
                        Instant.parse("2026-08-01T00:00:00Z"), null, null,
                        false, new CashClosureQueryRepository.CashClosureCursor(
                                "terminal a", Instant.parse("2026-07-31T17:00:00Z"), olderAId), 2);
                assertThat(secondPage).extracting(CashClosureQueryRepository.CashClosureRow::id)
                        .containsExactly(terminalBClosureId);

                var discrepanciesForUserOne = repository.findClosures(
                        storeId, Instant.parse("2026-07-31T00:00:00Z"),
                        Instant.parse("2026-08-01T00:00:00Z"), null, userOneId,
                        true, null, 10);
                assertThat(discrepanciesForUserOne)
                        .extracting(CashClosureQueryRepository.CashClosureRow::id)
                        .containsExactly(newestAId, terminalBClosureId);
                assertThat(repository.findTerminalOptions(storeId)).extracting(CashClosureFilterOptionView::name)
                        .containsExactly("TERMINAL A", "TERMINAL B");
                assertThat(repository.findUserOptions(storeId)).extracting(CashClosureFilterOptionView::name)
                        .containsExactly("USER ONE", "USER TWO");
            }
        } finally {
            try (var connection = DriverManager.getConnection(database.url(), database.user(), database.password());
                    var statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static void insertClosure(
            java.sql.Statement statement,
            UUID id,
            UUID storeId,
            UUID terminalId,
            UUID userId,
            String closedAt,
            String expectedCash,
            String retainedFund,
            String discrepancy) throws Exception {
        statement.execute("""
                insert into sesion_caja(
                    id,tienda_id,terminal_id,usuario_apertura_id,abierta_en,fondo_inicial,
                    usuario_cierre_id,cerrada_en,efectivo_teorico,fondo_dejado,descuadre,estado,cierre_tardio)
                values ('%s','%s','%s','%s','2026-07-31T08:00:00Z',50,
                        '%s','%s',%s,%s,%s,'CERRADA',false)
                """.formatted(
                id, storeId, terminalId, userId, userId, closedAt,
                expectedCash, retainedFund, discrepancy));
    }

    private record DatabaseEnvironment(String url, String user, String password) {
        private static DatabaseEnvironment resolve() {
            var url = first("TPV_TEST_DB_URL", "TPV_ERP_TEST_DB_URL");
            var user = first("TPV_TEST_DB_USERNAME", "TPV_ERP_TEST_DB_USER");
            var password = first("TPV_TEST_DB_PASSWORD", "TPV_ERP_TEST_DB_PASSWORD");
            return url == null || user == null || password == null
                    ? null
                    : new DatabaseEnvironment(url, user, password);
        }

        private static String first(String primary, String legacy) {
            var value = System.getenv(primary);
            return value == null || value.isBlank() ? System.getenv(legacy) : value;
        }
    }
}

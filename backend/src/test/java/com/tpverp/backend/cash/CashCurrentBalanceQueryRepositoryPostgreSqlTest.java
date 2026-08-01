package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class CashCurrentBalanceQueryRepositoryPostgreSqlTest {

    @Test
    void calculatesOpenClosedAndNeverOpenedTerminalBalancesWithoutReapplyingOldMovements() throws Exception {
        var database = DatabaseEnvironment.resolve();
        assumeTrue(database != null, "Configure TPV_TEST_DB_* o TPV_ERP_TEST_DB_* para PostgreSQL");
        var schema = "cash_current_balance_" + UUID.randomUUID().toString().replace("-", "");
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var openTerminalId = UUID.randomUUID();
        var closedTerminalId = UUID.randomUUID();
        var newTerminalId = UUID.randomUUID();
        var inactiveTerminalId = UUID.randomUUID();
        var openSessionId = UUID.randomUUID();
        var closedSessionId = UUID.randomUUID();
        try {
            Flyway.configure()
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
                            insert into terminal(id,tienda_id,nombre,tipo,credential_hash,activa,aprobada)
                            values ('%s','%s','TPV ABIERTA','TERMINAL_VENTA','h',true,true),
                                   ('%s','%s','TPV CERRADA','TERMINAL_VENTA','h',true,true),
                                   ('%s','%s','TPV NUEVA','TERMINAL_VENTA','h',true,true),
                                   ('%s','%s','TPV INACTIVA','TERMINAL_VENTA','h',false,true)
                            """.formatted(
                            openTerminalId, storeId, closedTerminalId, storeId,
                            newTerminalId, storeId, inactiveTerminalId, storeId));
                    statement.execute("insert into rol(id,tienda_id,nombre) values ('%s','%s','SELLER')"
                            .formatted(roleId, storeId));
                    statement.execute("""
                            insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id)
                            values ('%s','%s','CAJERO','cajero','h','%s')
                            """.formatted(userId, storeId, roleId));
                    statement.execute("""
                            insert into sesion_caja(id,tienda_id,terminal_id,usuario_apertura_id,abierta_en,
                                                   fondo_inicial,estado,cierre_tardio)
                            values ('%s','%s','%s','%s','2026-08-01T08:00:00Z',50,'ABIERTA',false)
                            """.formatted(openSessionId, storeId, openTerminalId, userId));
                    statement.execute("""
                            insert into sesion_caja(id,tienda_id,terminal_id,usuario_apertura_id,abierta_en,
                                                   fondo_inicial,usuario_cierre_id,cerrada_en,efectivo_teorico,
                                                   fondo_dejado,descuadre,estado,cierre_tardio)
                            values ('%s','%s','%s','%s','2026-07-31T08:00:00Z',20,'%s',
                                    '2026-07-31T18:00:00Z',30,30,0,'CERRADA',false)
                            """.formatted(closedSessionId, storeId, closedTerminalId, userId, userId));
                    insertMovement(statement, storeId, openTerminalId, openSessionId, userId,
                            "COBRO_EFECTIVO", "100", "2026-08-01T09:00:00Z");
                    insertMovement(statement, storeId, openTerminalId, openSessionId, userId,
                            "DEVOLUCION_EFECTIVO", "10", "2026-08-01T09:15:00Z");
                    insertMovement(statement, storeId, openTerminalId, openSessionId, userId,
                            "ENTRADA", "5", "2026-08-01T09:30:00Z");
                    insertMovement(statement, storeId, openTerminalId, openSessionId, userId,
                            "RETIRADA", "20", "2026-08-01T10:00:00Z");
                    insertMovement(statement, storeId, closedTerminalId, null, userId,
                            "ENTRADA_ENTRE_SESIONES", "100", "2026-07-30T19:00:00Z");
                    insertMovement(statement, storeId, closedTerminalId, null, userId,
                            "ENTRADA_ENTRE_SESIONES", "10", "2026-07-31T19:00:00Z");
                    insertMovement(statement, storeId, closedTerminalId, null, userId,
                            "RETIRADA_ENTRE_SESIONES", "5", "2026-07-31T20:00:00Z");
                    insertMovement(statement, storeId, newTerminalId, null, userId,
                            "ENTRADA_ENTRE_SESIONES", "12", "2026-08-01T08:30:00Z");
                }
                var repository = new CashCurrentBalanceQueryRepository(new NamedParameterJdbcTemplate(
                        new SingleConnectionDataSource(connection, true)));

                var result = repository.findCurrentBalances(storeId);

                assertThat(result).extracting(CashCurrentBalanceView::terminalName)
                        .containsExactly("TPV ABIERTA", "TPV CERRADA", "TPV NUEVA");
                assertThat(result.get(0).status()).isEqualTo(CashCurrentBalanceStatus.ABIERTA);
                assertThat(result.get(0).openingUserName()).isEqualTo("CAJERO");
                assertThat(result.get(0).expectedCash()).isEqualByComparingTo("125.00");
                assertThat(result.get(1).status()).isEqualTo(CashCurrentBalanceStatus.CERRADA);
                assertThat(result.get(1).expectedCash()).isEqualByComparingTo("35.00");
                assertThat(result.get(2).status()).isEqualTo(CashCurrentBalanceStatus.SIN_SESION);
                assertThat(result.get(2).expectedCash()).isEqualByComparingTo("12.00");
            }
        } finally {
            try (var connection = DriverManager.getConnection(database.url(), database.user(), database.password());
                    var statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static void insertMovement(
            java.sql.Statement statement,
            UUID storeId,
            UUID terminalId,
            UUID sessionId,
            UUID userId,
            String type,
            String amount,
            String createdAt) throws Exception {
        var session = sessionId == null ? "null" : "'%s'".formatted(sessionId);
        statement.execute("""
                insert into movimiento_caja(id,tienda_id,terminal_id,sesion_caja_id,tipo,importe,creado_en,usuario_id)
                values ('%s','%s','%s',%s,'%s',%s,'%s','%s')
                """.formatted(UUID.randomUUID(), storeId, terminalId, session, type, amount, createdAt, userId));
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

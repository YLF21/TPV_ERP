package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class CashCloseOperationMigrationPostgreSqlTest {

    @Test
    void upgradesARealV117CloseWithoutInventingHistoricalAttemptKeys() throws Exception {
        var database = DatabaseEnvironment.resolve();
        assumeTrue(database != null, "Configure TPV_TEST_DB_* o TPV_ERP_TEST_DB_* para PostgreSQL");
        var schema = "cash_close_operation_" + UUID.randomUUID().toString().replace("-", "");
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var terminalId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var sessionId = UUID.randomUUID();
        var movementId = UUID.randomUUID();
        var operationId = UUID.randomUUID();
        var attemptId = UUID.randomUUID();
        try {
            FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                    .dataSource(database.url(), database.user(), database.password())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .target("117")
                    .load()
                    .migrate();
            try (var connection = DriverManager.getConnection(
                    database.url(), database.user(), database.password());
                    var statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema);
                statement.execute("""
                        insert into empresa(id,tax_id,razon_social,domicilio_fiscal)
                        values ('%s','B1','Test','{"linea1":"x","ciudad":"x","codigoPostal":"1","provincia":"x","pais":"ES"}')
                        """.formatted(companyId));
                statement.execute("""
                        insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda)
                        values ('%s','%s','T','{"linea1":"x","ciudad":"x","codigoPostal":"1","provincia":"x","pais":"ES"}',
                                'h','Europe/Madrid','EUR','es-ES','001')
                        """.formatted(storeId, companyId));
                statement.execute("""
                        insert into terminal(id,tienda_id,nombre,tipo,credential_hash)
                        values ('%s','%s','TPV','TERMINAL_VENTA','h')
                        """.formatted(terminalId, storeId));
                statement.execute("""
                        insert into rol(id,tienda_id,nombre) values ('%s','%s','SELLER')
                        """.formatted(roleId, storeId));
                statement.execute("""
                        insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id)
                        values ('%s','%s','SELLER','Seller','h','%s')
                        """.formatted(userId, storeId, roleId));
                statement.execute("""
                        insert into sesion_caja(
                            id,tienda_id,terminal_id,usuario_apertura_id,abierta_en,fondo_inicial,estado,cierre_tardio)
                        values ('%s','%s','%s','%s',now(),100,'ABIERTA',false)
                        """.formatted(sessionId, storeId, terminalId, userId));
                statement.execute("""
                        insert into movimiento_caja(
                            id,tienda_id,terminal_id,sesion_caja_id,tipo,importe,creado_en,usuario_id,comentario)
                        values ('%s','%s','%s','%s','RETIRADA_CIERRE',20,now(),'%s','Cierre')
                        """.formatted(movementId, storeId, terminalId, sessionId, userId));
                statement.execute("""
                        insert into idempotencia_retirada_cierre(
                            clave_idempotencia,tienda_id,terminal_id,sesion_caja_id,movimiento_caja_id,
                            huella_solicitud,creado_en)
                        values ('%s','%s','%s','%s','%s',repeat('a',64),now())
                        """.formatted(operationId, storeId, terminalId, sessionId, movementId));
                statement.execute("""
                        insert into intento_arqueo_caja(
                            id,sesion_caja_id,numero_intento,usuario_id,creado_en,fondo_declarado,
                            efectivo_teorico,descuadre,cerro_sesion)
                        values ('%s','%s',1,'%s',now(),70,80,-10,false)
                        """.formatted(attemptId, sessionId, userId));
            }

            FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                    .dataSource(database.url(), database.user(), database.password())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(
                    database.url(), database.user(), database.password());
                    var statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema);
                try (var operation = statement.executeQuery("""
                        select id, movimiento_retirada_id, importe_retirada, estado
                        from operacion_cierre_caja
                        where sesion_caja_id = '%s'
                        """.formatted(sessionId))) {
                    assertThat(operation.next()).isTrue();
                    assertThat(operation.getObject("id", UUID.class)).isEqualTo(operationId);
                    assertThat(operation.getObject("movimiento_retirada_id", UUID.class)).isEqualTo(movementId);
                    assertThat(operation.getBigDecimal("importe_retirada")).isEqualByComparingTo("20.00");
                    assertThat(operation.getString("estado")).isEqualTo("REQUIERE_ARQUEO");
                }
                try (var attempt = statement.executeQuery("""
                        select operacion_cierre_id, clave_idempotencia, huella_solicitud
                        from intento_arqueo_caja where id = '%s'
                        """.formatted(attemptId))) {
                    assertThat(attempt.next()).isTrue();
                    assertThat(attempt.getObject("operacion_cierre_id", UUID.class)).isEqualTo(operationId);
                    assertThat(attempt.getObject("clave_idempotencia")).isNull();
                    assertThat(attempt.getString("huella_solicitud")).isNull();
                }
            }
        } finally {
            try (var connection = DriverManager.getConnection(
                    database.url(), database.user(), database.password());
                    var statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
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

package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
class ControlRuleRetirementMigrationPostgreSqlTest {
    @Test
    void upgradingExistingRulesPreservesOriginalValuesAndInitializesRetirementSafely() throws Exception {
        var url = System.getenv("TPV_ERP_TEST_DB_URL");
        var user = System.getenv("TPV_ERP_TEST_DB_USER");
        var password = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
        var schema = "control_rule_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var ruleId = UUID.randomUUID();
        var address = "{\"linea1\":\"x\",\"ciudad\":\"x\",\"codigoPostal\":\"1\",\"provincia\":\"x\",\"pais\":\"ES\"}";
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement()) {
            try {
                FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                        .dataSource(url, user, password).schemas(schema).defaultSchema(schema)
                        .createSchemas(true).target("272").load().migrate();
                statement.execute("set search_path to " + schema + ",public");
                statement.execute("""
                        insert into empresa(id,tax_id,razon_social,domicilio_fiscal)
                        values ('%s','B00000001','Migration test','%s')
                        """.formatted(companyId, address));
                statement.execute("""
                        insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,
                            timezone,moneda,locale,codigo_tienda)
                        values ('%s','%s','Migration test','%s','migration-test','Atlantic/Canary','EUR','es-ES','001')
                        """.formatted(storeId, companyId, address));
                statement.execute("insert into rol(id,tienda_id,nombre) values ('%s','%s','TEST')"
                        .formatted(roleId, storeId));
                statement.execute("""
                        insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id)
                        values ('%s','%s','TEST','test','test-only','%s')
                        """.formatted(userId, storeId, roleId));
                statement.execute("""
                        insert into control_regla(id,tienda_id,tipo,nombre,activa,configuracion,numero_version,
                            creado_por,actualizado_por,creado_en,actualizado_en)
                        values ('%s','%s','TICKET_CANCELLED','Anulacion de ticket',true,'{}',1,
                            '%s','%s',now(),now())
                        """.formatted(ruleId, storeId, userId, userId));
                statement.execute("""
                        insert into control_regla_version(id,regla_id,tienda_id,numero_version,tipo,nombre,
                            activa,configuracion,cambiado_por,cambiado_en)
                        select gen_random_uuid(),id,tienda_id,numero_version,tipo,nombre,
                            activa,configuracion,actualizado_por,actualizado_en from control_regla
                        """);
                var ruleBefore = json(statement, "control_regla");
                var versionBefore = json(statement, "control_regla_version");

                FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                        .dataSource(url, user, password).schemas(schema).defaultSchema(schema).load().migrate();

                assertThat(json(statement, "control_regla")).isEqualTo(ruleBefore);
                assertThat(json(statement, "control_regla_version")).isEqualTo(versionBefore);
                try (var result = statement.executeQuery("select retirada from control_regla union all "
                        + "select retirada from control_regla_version")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isFalse();
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isFalse();
                    assertThat(result.next()).isFalse();
                }
                assertThatThrownBy(() -> statement.execute("update control_regla set retirada=true"))
                        .isInstanceOf(SQLException.class).satisfies(error ->
                                assertThat(((SQLException) error).getSQLState()).isEqualTo("23514"));
                assertThatThrownBy(() -> statement.execute("""
                        insert into control_regla_version(id,regla_id,tienda_id,numero_version,tipo,nombre,
                            activa,retirada,configuracion,cambiado_por,cambiado_en)
                        select gen_random_uuid(),id,tienda_id,2,tipo,nombre,true,true,configuracion,
                            actualizado_por,actualizado_en from control_regla
                        """)).isInstanceOf(SQLException.class).satisfies(error ->
                                assertThat(((SQLException) error).getSQLState()).isEqualTo("23514"));
            } finally {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static String json(Statement statement, String table) throws Exception {
        try (var result = statement.executeQuery("select (to_jsonb(row)-'retirada')::text from " + table + " row")) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}

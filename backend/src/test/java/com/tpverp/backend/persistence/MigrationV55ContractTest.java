package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class MigrationV55ContractTest {

    private static final String MIGRATION =
            "db/migration/V55__preferencias_columnas_stock_usuario_protegido.sql";

    @Test
    void replacesTheStoreAssignmentForeignKeyWithAUserForeignKey() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("drop constraint preferencia_columnas_stock_usuario_tienda_fk")
                .contains("constraint preferencia_columnas_stock_usuario_fk")
                .contains("foreign key (usuario_id)")
                .contains("references usuario(id) on delete cascade");
    }

    @Test
    void protectedAdminCanSavePreferencesWithoutAnExplicitStoreAssignment() throws Exception {
        String url = setting(
                "TPV_ERP_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
        String user = setting("TPV_ERP_TEST_DB_USER", "postgres");
        String password = setting("TPV_ERP_TEST_DB_PASSWORD", "admin");
        assumeTrue(canConnect(url, user, password), "PostgreSQL de pruebas no disponible");
        String schema = "tpv_erp_v55_" + UUID.randomUUID().toString().replace("-", "");

        try {
            Flyway.configure()
                    .dataSource(url, user, password)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .load()
                    .migrate();
            assertProtectedAdminPreference(url, user, password, schema);
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static void assertProtectedAdminPreference(
            String url, String user, String password, String schema) throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID preferenceId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            statement.executeUpdate("""
                    insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                    values ('%s', 'B55000001', 'Company V55', '{
                      "linea1":"Calle Uno","ciudad":"Las Palmas",
                      "codigoPostal":"35001","provincia":"Las Palmas","pais":"ES"
                    }')
                    """.formatted(companyId));
            statement.executeUpdate("""
                    insert into tienda (
                        id, empresa_id, codigo_tienda, nombre, direccion,
                        address_normalized_hash, timezone, moneda, locale)
                    values ('%s', '%s', '001', 'Store', '{
                      "linea1":"Calle Uno","ciudad":"Las Palmas",
                      "codigoPostal":"35001","provincia":"Las Palmas","pais":"ES"
                    }', 'hash-v55', 'Atlantic/Canary', 'EUR', 'es-ES')
                    """.formatted(storeId, companyId));
            statement.executeUpdate("""
                    insert into rol (id, tienda_id, nombre, protegido)
                    values ('%s', '%s', 'ADMIN', true)
                    """.formatted(roleId, storeId));
            statement.executeUpdate("""
                    insert into usuario (
                        id, tienda_id, nombre, password_hash, rol_id, protegido,
                        user_id, user_name)
                    values (
                        '%s', '%s', 'ADMIN', 'hash', '%s', true,
                        'E-955001', 'Admin V55')
                    """.formatted(adminId, storeId, roleId));
            statement.executeUpdate("""
                    insert into preferencia_columnas_stock (
                        id, empresa_id, tienda_id, usuario_id, app, columnas,
                        creada_en, actualizada_en)
                    values (
                        '%s', '%s', '%s', '%s', 'venta',
                        '{"stock.current":[{"key":"name","width":220}]}',
                        now(), now())
                    """.formatted(preferenceId, companyId, storeId, adminId));

            try (var result = statement.executeQuery(
                    "select count(*) from preferencia_columnas_stock where id = '"
                            + preferenceId + "'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isOne();
            }

            statement.executeUpdate("delete from usuario where id = '" + adminId + "'");
            try (var result = statement.executeQuery(
                    "select count(*) from preferencia_columnas_stock where id = '"
                            + preferenceId + "'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
        }
    }

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean canConnect(String url, String user, String password) {
        try (Connection ignored = DriverManager.getConnection(url, user, password)) {
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}

package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class MigrationV53ContractTest {

    private static final String MIGRATION =
            "db/migration/V53__preferencias_columnas_stock.sql";

    @Test
    void createsValidatedScopedStockColumnPreferences() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("create table preferencia_columnas_stock")
                .contains("empresa_id uuid not null")
                .contains("tienda_id uuid not null")
                .contains("usuario_id uuid not null")
                .contains("columnas jsonb not null")
                .contains("foreign key (tienda_id, empresa_id)")
                .contains("references tienda(id, empresa_id)")
                .contains("foreign key (usuario_id, tienda_id)")
                .contains("references usuario_tienda(usuario_id, tienda_id)")
                .contains("app in ('venta', 'gestion')")
                .contains("jsonb_typeof(columnas) = 'object'")
                .contains("unique (tienda_id, usuario_id, app)")
                .contains("ix_preferencia_columnas_stock_scope")
                .doesNotContain("version bigint");
    }

    @Test
    void enforcesJsonAppOwnershipAndOneRowPerScopedUserAndApp() throws Exception {
        String url = setting(
                "TPV_ERP_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
        String user = setting("TPV_ERP_TEST_DB_USER", "postgres");
        String password = setting("TPV_ERP_TEST_DB_PASSWORD", "admin");
        assumeTrue(canConnect(url, user, password), "PostgreSQL de pruebas no disponible");
        String schema = "tpv_erp_v53_" + UUID.randomUUID().toString().replace("-", "");

        try {
            Flyway.configure()
                    .dataSource(url, user, password)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .target("53")
                    .load()
                    .migrate();
            assertConstraints(url, user, password, schema);
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static void assertConstraints(
            String url, String user, String password, String schema) throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID otherCompanyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID otherStoreId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID preferenceId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            statement.executeUpdate(companyInsert(companyId, "B53000001", "Company V53"));
            statement.executeUpdate(companyInsert(otherCompanyId, "B53000002", "Other Company V53"));
            statement.executeUpdate(storeInsert(storeId, companyId, "001", "hash-v53-1"));
            statement.executeUpdate(storeInsert(otherStoreId, companyId, "002", "hash-v53-2"));
            statement.executeUpdate("""
                    insert into rol (id, tienda_id, nombre, protegido)
                    values ('%s', '%s', 'GESTOR', false)
                    """.formatted(roleId, storeId));
            statement.executeUpdate("""
                    insert into usuario (
                        id, tienda_id, nombre, password_hash, rol_id, user_id, user_name)
                    values ('%s', '%s', 'GESTOR', 'hash', '%s', 'E-953001', 'Gestor V53')
                    """.formatted(userId, storeId, roleId));
            statement.executeUpdate("""
                    insert into usuario_tienda (usuario_id, tienda_id)
                    values ('%s', '%s')
                    """.formatted(userId, storeId));
            statement.executeUpdate(preferenceInsert(
                    preferenceId,
                    companyId,
                    storeId,
                    userId,
                    "venta",
                    "{\"stock.current\":[{\"key\":\"name\",\"width\":220}]}"));

            assertSqlState(statement, preferenceInsert(
                    UUID.randomUUID(),
                    companyId,
                    storeId,
                    userId,
                    "venta",
                    "{\"stock.current\":[{\"key\":\"code\",\"width\":110}]}"), "23505");
            assertSqlState(statement, preferenceInsert(
                    UUID.randomUUID(),
                    companyId,
                    storeId,
                    userId,
                    "otra",
                    "{\"stock.current\":[{\"key\":\"name\",\"width\":220}]}"), "23514");
            assertSqlState(statement, preferenceInsert(
                    UUID.randomUUID(),
                    companyId,
                    storeId,
                    userId,
                    "gestion",
                    "[]"), "23514");
            assertSqlState(statement, preferenceInsert(
                    UUID.randomUUID(),
                    otherCompanyId,
                    storeId,
                    userId,
                    "gestion",
                    "{\"stock.current\":[{\"key\":\"name\",\"width\":220}]}"), "23503");
            assertSqlState(statement, preferenceInsert(
                    UUID.randomUUID(),
                    companyId,
                    otherStoreId,
                    userId,
                    "gestion",
                    "{\"stock.current\":[{\"key\":\"name\",\"width\":220}]}"), "23503");

            statement.executeUpdate("delete from usuario_tienda where usuario_id = '"
                    + userId + "' and tienda_id = '" + storeId + "'");
            try (var result = statement.executeQuery(
                    "select count(*) from preferencia_columnas_stock where id = '"
                            + preferenceId + "'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
        }
    }

    private static String companyInsert(UUID id, String taxId, String name) {
        return """
                insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                values ('%s', '%s', '%s', '{
                  "linea1":"Calle Uno","ciudad":"Las Palmas",
                  "codigoPostal":"35001","provincia":"Las Palmas","pais":"ES"
                }')
                """.formatted(id, taxId, name);
    }

    private static String storeInsert(UUID id, UUID companyId, String code, String hash) {
        return """
                insert into tienda (
                    id, empresa_id, codigo_tienda, nombre, direccion,
                    address_normalized_hash, timezone, moneda, locale)
                values ('%s', '%s', '%s', 'Store', '{
                  "linea1":"Calle Uno","ciudad":"Las Palmas",
                  "codigoPostal":"35001","provincia":"Las Palmas","pais":"ES"
                }', '%s', 'Atlantic/Canary', 'EUR', 'es-ES')
                """.formatted(id, companyId, code, hash);
    }

    private static String preferenceInsert(
            UUID id,
            UUID companyId,
            UUID storeId,
            UUID userId,
            String app,
            String columns) {
        return """
                insert into preferencia_columnas_stock (
                    id, empresa_id, tienda_id, usuario_id, app, columnas,
                    creada_en, actualizada_en)
                values ('%s', '%s', '%s', '%s', '%s', '%s', now(), now())
                """.formatted(id, companyId, storeId, userId, app, columns);
    }

    private static void assertSqlState(Statement statement, String sql, String state) throws Exception {
        try {
            statement.executeUpdate(sql);
            throw new AssertionError("Se esperaba SQLSTATE " + state);
        } catch (SQLException exception) {
            assertThat(exception.getSQLState()).isEqualTo(state);
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

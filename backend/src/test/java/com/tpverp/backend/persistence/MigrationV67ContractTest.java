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
import java.util.ArrayList;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class MigrationV67ContractTest {

    private static final String MIGRATION =
            "db/migration/V67__preferencias_diseno_tabla_usuario.sql";

    @Test
    void createsGenericUserScopedTableLayoutPreferencesAndKeepsLegacyTables() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("create table preferencia_diseno_tabla")
                .contains("usuario_id uuid not null references usuario(id) on delete cascade")
                .contains("app varchar(16) not null")
                .contains("table_key varchar(128) not null")
                .contains("columnas jsonb not null")
                .contains("created_at timestamptz not null")
                .contains("updated_at timestamptz not null")
                .contains("version bigint not null default 0")
                .contains("app in ('venta', 'gestion')")
                .contains("table_key ~ '^[a-za-z0-9._-]+$'")
                .contains("jsonb_array_length(columnas) <= 128")
                .contains("unique (usuario_id, app, table_key)")
                .contains("has_table_privilege")
                .contains("preferencia_visualizacion_informe")
                .doesNotContain("drop table preferencia_columnas_stock")
                .doesNotContain("drop table preferencia_visualizacion_informe");
    }

    @Test
    void backfillsStockViewsAndOrderedReportColumns() throws Exception {
        String url = setting(
                "TPV_ERP_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
        String user = setting("TPV_ERP_TEST_DB_USER", "postgres");
        String password = setting("TPV_ERP_TEST_DB_PASSWORD", "admin");
        assumeTrue(canConnect(url, user, password), "PostgreSQL de pruebas no disponible");
        String schema = "tpv_erp_v67_" + UUID.randomUUID().toString().replace("-", "");

        try {
            migrateTo(url, user, password, schema, "66");
            seedLegacyPreferences(url, user, password, schema);
            migrateTo(url, user, password, schema, "67");
            assertBackfill(url, user, password, schema);
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static void migrateTo(
            String url,
            String user,
            String password,
            String schema,
            String target) {
        Flyway.configure()
                .dataSource(url, user, password)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .target(target)
                .load()
                .migrate();
    }

    private static void seedLegacyPreferences(
            String url, String user, String password, String schema) throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID firstStoreId = UUID.randomUUID();
        UUID secondStoreId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            statement.executeUpdate("""
                    insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                    values ('%s', 'B67000001', 'Company V67', '{
                      "linea1":"Calle Uno","ciudad":"Las Palmas",
                      "codigoPostal":"35001","provincia":"Las Palmas","pais":"ES"
                    }')
                    """.formatted(companyId));
            statement.executeUpdate(storeInsert(
                    firstStoreId, companyId, "001", "hash-v67-1", "Store One"));
            statement.executeUpdate(storeInsert(
                    secondStoreId, companyId, "002", "hash-v67-2", "Store Two"));
            statement.executeUpdate("""
                    insert into rol (id, tienda_id, nombre, protegido)
                    values ('%s', '%s', 'GESTOR', false)
                    """.formatted(roleId, firstStoreId));
            statement.executeUpdate("""
                    insert into usuario (
                        id, tienda_id, nombre, password_hash, rol_id, user_id, user_name)
                    values ('%s', '%s', 'GESTOR', 'hash', '%s', 'E-967001', 'Gestor V67')
                    """.formatted(userId, firstStoreId, roleId));

            statement.executeUpdate(stockPreferenceInsert(
                    UUID.randomUUID(),
                    companyId,
                    firstStoreId,
                    userId,
                    "2026-07-15T10:00:00Z",
                    """
                    {
                      "stock.current":[
                        {"key":"name","width":220},
                        {"key":"code","width":100,"visible":false}
                      ],
                      "stock.topSales":[
                        {"key":"sold","width":90,"visible":true}
                      ]
                    }
                    """));
            statement.executeUpdate(stockPreferenceInsert(
                    UUID.randomUUID(),
                    companyId,
                    secondStoreId,
                    userId,
                    "2026-07-15T11:00:00Z",
                    """
                    {
                      "stock.current":[
                        {"key":"code","width":130,"visible":false},
                        {"key":"name","width":260}
                      ]
                    }
                    """));
            statement.executeUpdate("""
                    insert into preferencia_visualizacion_informe (
                        id, usuario_id, app, report_key, visible_attributes,
                        created_at, updated_at, version)
                    values (
                        '%s', '%s', 'venta', 'sales.daily',
                        '["date","ticket","total"]',
                        '2026-07-15T09:00:00Z', '2026-07-15T09:30:00Z', 0)
                    """.formatted(UUID.randomUUID(), userId));
        }
    }

    private static String storeInsert(
            UUID id, UUID companyId, String code, String hash, String name) {
        return """
                insert into tienda (
                    id, empresa_id, codigo_tienda, nombre, direccion,
                    address_normalized_hash, timezone, moneda, locale)
                values ('%s', '%s', '%s', '%s', '{
                  "linea1":"Calle Uno","ciudad":"Las Palmas",
                  "codigoPostal":"35001","provincia":"Las Palmas","pais":"ES"
                }', '%s', 'Atlantic/Canary', 'EUR', 'es-ES')
                """.formatted(id, companyId, code, name, hash);
    }

    private static String stockPreferenceInsert(
            UUID id,
            UUID companyId,
            UUID storeId,
            UUID userId,
            String updatedAt,
            String columns) {
        return """
                insert into preferencia_columnas_stock (
                    id, empresa_id, tienda_id, usuario_id, app, columnas,
                    creada_en, actualizada_en)
                values (
                    '%s', '%s', '%s', '%s', 'venta', '%s',
                    '2026-07-15T08:00:00Z', '%s')
                """.formatted(id, companyId, storeId, userId, columns, updatedAt);
    }

    private static void assertBackfill(
            String url, String user, String password, String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);

            var tableKeys = new ArrayList<String>();
            try (var result = statement.executeQuery("""
                    select table_key
                    from preferencia_diseno_tabla
                    order by table_key
                    """)) {
                while (result.next()) {
                    tableKeys.add(result.getString(1));
                }
            }
            assertThat(tableKeys).containsExactly(
                    "reports.sales.daily", "stock.current", "stock.topSales");

            try (var stock = statement.executeQuery("""
                    select
                        columnas->0->>'key' as first_key,
                        (columnas->0->>'width')::integer as first_width,
                        (columnas->0->>'visible')::boolean as first_visible,
                        columnas->1->>'key' as second_key,
                        (columnas->1->>'width')::integer as second_width,
                        (columnas->1) ? 'visible' as second_has_visible
                    from preferencia_diseno_tabla
                    where table_key = 'stock.current'
                    """)) {
                assertThat(stock.next()).isTrue();
                assertThat(stock.getString("first_key")).isEqualTo("code");
                assertThat(stock.getInt("first_width")).isEqualTo(130);
                assertThat(stock.getBoolean("first_visible")).isFalse();
                assertThat(stock.getString("second_key")).isEqualTo("name");
                assertThat(stock.getInt("second_width")).isEqualTo(260);
                assertThat(stock.getBoolean("second_has_visible")).isFalse();
            }

            try (var report = statement.executeQuery("""
                    select
                        columnas->0->>'key' as first_key,
                        columnas->1->>'key' as second_key,
                        columnas->2->>'key' as third_key,
                        jsonb_array_length(columnas) as column_count,
                        (columnas->0) ? 'width' as has_width,
                        (columnas->0->>'visible')::boolean as visible
                    from preferencia_diseno_tabla
                    where table_key = 'reports.sales.daily'
                    """)) {
                assertThat(report.next()).isTrue();
                assertThat(report.getString("first_key")).isEqualTo("date");
                assertThat(report.getString("second_key")).isEqualTo("ticket");
                assertThat(report.getString("third_key")).isEqualTo("total");
                assertThat(report.getInt("column_count")).isEqualTo(3);
                assertThat(report.getBoolean("has_width")).isFalse();
                assertThat(report.getBoolean("visible")).isTrue();
            }

            assertSqlState(statement, """
                    insert into preferencia_diseno_tabla (
                        id, usuario_id, app, table_key, columnas,
                        created_at, updated_at, version)
                    select gen_random_uuid(), usuario_id, app, 'invalid/key', '[]',
                           now(), now(), 0
                    from preferencia_diseno_tabla limit 1
                    """, "23514");
        }
    }

    private static void assertSqlState(
            Statement statement, String sql, String expectedState) throws Exception {
        try {
            statement.executeUpdate(sql);
            throw new AssertionError("Se esperaba SQLSTATE " + expectedState);
        } catch (SQLException exception) {
            assertThat(exception.getSQLState()).isEqualTo(expectedState);
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

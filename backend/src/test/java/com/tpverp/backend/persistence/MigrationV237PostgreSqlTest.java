package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real PostgreSQL upgrade coverage for the normalized hierarchy-search indexes. */
class MigrationV237PostgreSqlTest {

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void upgradesV236WithoutChangingItsCatalogInvariantsAndCreatesReadyIndexes()
            throws Exception {
        String url = setting("TPV_ERP_TEST_DB_URL",
                "jdbc:postgresql://localhost:5432/tpv_erp_test");
        String user = setting("TPV_ERP_TEST_DB_USER", "tpv_erp_test");
        String password = setting("TPV_ERP_TEST_DB_PASSWORD", "admin");
        assumeTrue(canConnect(url, user, password), "PostgreSQL de pruebas no disponible");
        String schema = "catalog_search_v237_"
                + UUID.randomUUID().toString().replace("-", "");
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        UUID subfamilyId = UUID.randomUUID();
        try {
            migrate(url, user, password, schema, "236");
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema + ", public");
                statement.executeUpdate(("""
                        insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                        values ('%s', 'B23700001', 'Empresa V237',
                          '{"linea1":"Test","ciudad":"Las Palmas","codigoPostal":"35001","provincia":"Las Palmas","pais":"ES"}')
                        """).formatted(companyId));
                statement.executeUpdate(("""
                        insert into tienda (id, empresa_id, codigo_tienda, nombre, direccion,
                          address_normalized_hash, timezone, moneda, locale)
                        values ('%s', '%s', '371', 'Tienda V237',
                          '{"linea1":"Test","ciudad":"Las Palmas","codigoPostal":"35001","provincia":"Las Palmas","pais":"ES"}',
                          'hash-v237', 'Atlantic/Canary', 'EUR', 'es-ES')
                        """).formatted(storeId, companyId));
                statement.executeUpdate(("""
                        insert into familia
                          (id, tienda_id, family_id, family_code, nombre, predeterminada)
                        values ('%s', '%s', 'LEGACY_FACADE', '017', 'FAÇANA', false)
                        """).formatted(familyId, storeId));
                statement.executeUpdate(("""
                        insert into subfamilia
                          (id, familia_id, subfamily_id, subfamily_suffix,
                           subfamily_code, nombre)
                        values ('%s', '%s', 'LEGACY_E_ACUTE', '004', '017004',
                          U&'E\\0301LECTRICA')
                        """).formatted(subfamilyId, familyId));
            }

            migrate(url, user, password, schema, "237");

            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema + ", public");

                assertThat(scalar(statement, """
                        select p.provolatile
                        from pg_proc p
                        join pg_namespace n on n.oid = p.pronamespace
                        where n.nspname = current_schema()
                          and p.proname = 'tpv_catalog_search_normalize'
                        """)).isEqualTo("i");
                assertThat(scalar(statement, """
                        select tpv_catalog_search_normalize(U&'Fa\\00E7ana')
                        """)).isEqualTo("FACANA");
                assertThat(scalar(statement, """
                        select tpv_catalog_search_normalize(U&'E\\0301lectrica')
                        """)).isEqualTo("ELECTRICA");

                List<String> expectedIndexes = List.of(
                        "ix_familia_search_nombre_normalizado_trgm",
                        "ix_familia_search_prefijo",
                        "ix_familia_search_codigo_prefijo",
                        "ix_subfamilia_search_prefijo",
                        "ix_subfamilia_search_codigo_prefijo",
                        "ix_subfamilia_search_nombre_normalizado_trgm");
                for (String index : expectedIndexes) {
                    assertThat(scalar(statement, ("""
                            select i.indisvalid::text || '|' || i.indisready::text
                            from pg_index i
                            join pg_class c on c.oid = i.indexrelid
                            join pg_namespace n on n.oid = c.relnamespace
                            where n.nspname = current_schema() and c.relname = '%s'
                            """).formatted(index))).isEqualTo("true|true");
                }
                assertThat(scalar(statement, """
                        select pg_get_indexdef(c.oid)
                        from pg_class c
                        join pg_namespace n on n.oid = c.relnamespace
                        where n.nspname = current_schema()
                          and c.relname = 'ix_familia_search_nombre_normalizado_trgm'
                        """)).contains("tpv_catalog_search_normalize")
                        .contains("gin_trgm_ops");
                assertThat(scalar(statement, """
                        select pg_get_indexdef(c.oid)
                        from pg_class c
                        join pg_namespace n on n.oid = c.relnamespace
                        where n.nspname = current_schema()
                          and c.relname = 'ix_subfamilia_search_prefijo'
                        """)).contains("tpv_catalog_search_normalize")
                        .contains("text_pattern_ops");

                // Structural/data invariants installed by V236 remain unchanged.
                assertThat(columnExists(statement, "familia", "orden")).isFalse();
                assertThat(columnExists(statement, "subfamilia", "orden")).isFalse();
                assertThat(constraintExists(statement, "fk_producto_tienda_familia")).isTrue();
                assertThat(constraintExists(statement, "fk_producto_familia_subfamilia")).isTrue();
                assertThat(triggerExists(statement, "tr_familia_guard_code_update")).isTrue();
                assertThat(triggerExists(statement, "tr_subfamilia_validate_code")).isTrue();
                assertThat(scalar(statement,
                        "select family_id || '|' || family_code || '|' || nombre "
                                + "from familia where id = '" + familyId + "'"))
                        .isEqualTo("LEGACY_FACADE|017|FAÇANA");
                assertThat(scalar(statement,
                        "select subfamily_id || '|' || subfamily_suffix || '|' "
                                + "|| subfamily_code from subfamilia where id = '"
                                + subfamilyId + "'"))
                        .isEqualTo("LEGACY_E_ACUTE|004|017004");
                assertThat(scalar(statement, """
                        select string_agg(version, ',' order by installed_rank)
                        from flyway_schema_history where version in ('236', '237')
                        """)).isEqualTo("236,237");
            }
        } finally {
            dropSchema(url, user, password, schema);
        }
    }

    private static boolean columnExists(java.sql.Statement statement, String table, String column)
            throws SQLException {
        return exists(statement, "select 1 from information_schema.columns "
                + "where table_schema=current_schema() and table_name='" + table
                + "' and column_name='" + column + "'");
    }

    private static boolean constraintExists(java.sql.Statement statement, String constraint)
            throws SQLException {
        return exists(statement, "select 1 from pg_constraint where conname='" + constraint + "'");
    }

    private static boolean triggerExists(java.sql.Statement statement, String trigger)
            throws SQLException {
        return exists(statement, "select 1 from pg_trigger where tgname='" + trigger
                + "' and not tgisinternal");
    }

    private static boolean exists(java.sql.Statement statement, String sql) throws SQLException {
        try (var rows = statement.executeQuery(sql)) {
            return rows.next();
        }
    }

    private static String scalar(java.sql.Statement statement, String sql) throws SQLException {
        try (var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static void migrate(
            String url, String user, String password, String schema, String target) {
        var configuration = FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                .dataSource(url, user, password)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .target(MigrationVersion.fromVersion(target));
        configuration.load().migrate();
    }

    private static void dropSchema(String url, String user, String password, String schema) {
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        } catch (Exception ignored) {
            // Preserve the original assertion failure.
        }
    }

    private static boolean canConnect(String url, String user, String password) {
        try (var ignored = DriverManager.getConnection(url, user, password)) {
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

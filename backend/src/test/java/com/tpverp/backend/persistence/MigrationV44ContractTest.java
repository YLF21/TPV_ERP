package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class MigrationV44ContractTest {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/tpv_erp_test";
    private static final String DEFAULT_USER = "postgres";
    private static final String DEFAULT_PASSWORD = "admin";

    @Test
    void promotionsTablesAndDocumentLineColumnsExist() throws Exception {
        String url = setting("TPV_ERP_TEST_DB_URL", DEFAULT_URL);
        String user = setting("TPV_ERP_TEST_DB_USER", DEFAULT_USER);
        String password = setting("TPV_ERP_TEST_DB_PASSWORD", DEFAULT_PASSWORD);
        assumeTrue(canConnect(url, user, password), "PostgreSQL de pruebas no disponible");

        String schema = "tpv_erp_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure()
                    .dataSource(url, user, password)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .load()
                    .migrate();

            try (Connection connection = DriverManager.getConnection(url, user, password)) {
                assertThat(column(connection, schema, "documento_linea", "tipo_linea")).isTrue();
                assertThat(column(connection, schema, "documento_linea", "promocion_id")).isTrue();
                assertThat(column(connection, schema, "documento_linea", "promocion_version_id")).isTrue();
                assertThat(column(connection, schema, "documento_linea", "cupon_promocional_id")).isTrue();
                assertThat(column(connection, schema, "documento_linea", "metadata_promocion")).isTrue();
                assertThat(columnLength(connection, schema, "documento_linea", "tipo_linea"))
                        .isGreaterThanOrEqualTo("PROMOTIONAL_COUPON".length());
                assertThat(table(connection, schema, "promocion")).isTrue();
                assertThat(table(connection, schema, "promocion_objetivo")).isTrue();
                assertThat(table(connection, schema, "cupon_promocional")).isTrue();
                assertThat(table(connection, schema, "cupon_promocional_intento")).isTrue();
                assertThat(constraints(connection, schema, "documento_linea")).contains(
                        "ck_documento_linea_tipo_linea",
                        "ck_documento_linea_product_integrity",
                        "ck_documento_linea_promotion_integrity",
                        "ck_documento_linea_coupon_integrity",
                        "ck_documento_linea_metadata_promocion");
                assertThat(indexes(connection, schema)).contains(
                        "ix_documento_linea_promocion",
                        "ix_documento_linea_promocion_version",
                        "ix_documento_linea_cupon_promocional");
            }
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
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

    private static int columnLength(
            Connection connection, String schema, String tableName, String columnName) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        select character_maximum_length
                        from information_schema.columns
                        where table_schema = '%s'
                          and table_name = '%s'
                          and column_name = '%s'
                        """.formatted(schema, tableName, columnName))) {
            assertThat(result.next()).isTrue();
            return result.getInt("character_maximum_length");
        }
    }

    private static ArrayList<String> constraints(Connection connection, String schema, String tableName)
            throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        select constraint_name
                        from information_schema.table_constraints
                        where constraint_schema = '%s'
                          and table_name = '%s'
                        order by constraint_name
                        """.formatted(schema, tableName))) {
            var names = new ArrayList<String>();
            while (result.next()) {
                names.add(result.getString("constraint_name"));
            }
            return names;
        }
    }

    private static ArrayList<String> indexes(Connection connection, String schema) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        select indexname
                        from pg_indexes
                        where schemaname = '%s'
                        order by indexname
                        """.formatted(schema))) {
            var names = new ArrayList<String>();
            while (result.next()) {
                names.add(result.getString("indexname"));
            }
            return names;
        }
    }

    private static boolean table(Connection connection, String schema, String tableName) throws Exception {
        try (ResultSet result = connection.getMetaData().getTables(null, schema, tableName, null)) {
            return result.next();
        }
    }

    private static boolean column(
            Connection connection, String schema, String tableName, String columnName) throws Exception {
        try (ResultSet result = connection.getMetaData().getColumns(null, schema, tableName, columnName)) {
            return result.next();
        }
    }
}

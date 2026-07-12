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

class MigrationV50ContractTest {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/tpv_erp_test";
    private static final String DEFAULT_USER = "postgres";
    private static final String DEFAULT_PASSWORD = "admin";

    @Test
    void completePromotionConfigurationIsPersistedAndConstrained() throws Exception {
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
                assertThat(column(connection, schema, "promocion", "modo_agrupacion_compra")).isTrue();
                assertThat(constraints(connection, schema, "promocion")).contains(
                        "ck_promocion_modo_agrupacion_compra",
                        "ck_promocion_configuracion_activa");
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

    private static ArrayList<String> constraints(
            Connection connection,
            String schema,
            String tableName) throws Exception {
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

    private static boolean column(
            Connection connection,
            String schema,
            String tableName,
            String columnName) throws Exception {
        try (ResultSet result = connection.getMetaData().getColumns(
                null, schema, tableName, columnName)) {
            return result.next();
        }
    }
}

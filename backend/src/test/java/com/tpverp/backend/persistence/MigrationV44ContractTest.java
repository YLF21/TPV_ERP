package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class MigrationV44ContractTest {

    private static final String URL = "jdbc:postgresql://localhost:5432/tpv_erp_test";
    private static final String USER = "postgres";
    private static final String PASSWORD = "admin";

    @Test
    void promotionsTablesAndDocumentLineColumnsExist() throws Exception {
        String schema = "tpv_erp_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure()
                    .dataSource(URL, USER, PASSWORD)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .load()
                    .migrate();

            try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
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
            }
        } finally {
            try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
                    Statement statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
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

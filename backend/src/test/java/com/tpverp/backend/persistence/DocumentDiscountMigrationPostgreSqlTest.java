package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class DocumentDiscountMigrationPostgreSqlTest {

    @Test
    void installsDocumentDiscountConstraintsFromAnEmptySchema() throws Exception {
        var url = setting("TPV_ERP_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
        var user = setting("TPV_ERP_TEST_DB_USER", "postgres");
        var password = setting("TPV_ERP_TEST_DB_PASSWORD", "admin");
        assumeTrue(canConnect(url, user, password), "PostgreSQL de pruebas no disponible");
        var schema = "document_discount_" + UUID.randomUUID().toString().replace("-", "");
        try {
            FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                    .dataSource(url, user, password)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema);
                try (var constraints = statement.executeQuery("""
                        select conname, pg_get_constraintdef(oid) definition
                        from pg_constraint
                        where conrelid = 'documento_linea'::regclass
                          and conname in (
                            'ck_documento_linea_tipo_linea',
                            'ck_documento_linea_document_discount_integrity')
                        order by conname
                        """)) {
                    assertThat(constraints.next()).isTrue();
                    assertThat(constraints.getString("conname"))
                            .isEqualTo("ck_documento_linea_document_discount_integrity");
                    assertThat(constraints.getString("definition"))
                            .contains("DOCUMENT_DISCOUNT", "documento_ajuste_id", "linea_origen_id");
                    assertThat(constraints.next()).isTrue();
                    assertThat(constraints.getString("conname"))
                            .isEqualTo("ck_documento_linea_tipo_linea");
                    assertThat(constraints.getString("definition"))
                            .contains("DOCUMENT_DISCOUNT");
                    assertThat(constraints.next()).isFalse();
                }
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
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
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PaymentPlatformMigrationPostgreSqlTest {

    @Test
    void installsPaymentPlatformFromAnEmptySchema() throws Exception {
        migrateAndVerify(null);
    }

    @Test
    void upgradesAProductionCompatibleV45SchemaToV61() throws Exception {
        migrateAndVerify("45");
    }

    private static void migrateAndVerify(String startingVersion) throws Exception {
        var database = DatabaseEnvironment.resolve();
        assumeTrue(database != null, "Configure TPV_TEST_DB_* o TPV_ERP_TEST_DB_* para PostgreSQL");
        var schema = "payment_platform_" + UUID.randomUUID().toString().replace("-", "");
        try {
            if (startingVersion != null) {
                Flyway.configure()
                        .dataSource(database.url(), database.user(), database.password())
                        .schemas(schema)
                        .defaultSchema(schema)
                        .createSchemas(true)
                        .target(startingVersion)
                        .load()
                        .migrate();
            }
            Flyway.configure()
                    .dataSource(database.url(), database.user(), database.password())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(database.url(), database.user(), database.password());
                    var statement = connection.createStatement()) {
                try (var history = statement.executeQuery("""
                        select version from %s.flyway_schema_history
                        where success order by installed_rank desc limit 1
                        """.formatted(schema))) {
                    assertThat(history.next()).isTrue();
                    assertThat(history.getString(1)).isEqualTo("61");
                }
                try (var tables = statement.executeQuery("""
                            select table_name
                            from information_schema.tables
                            where table_schema = '%s'
                              and table_name in (
                                'configuracion_pago_terminal',
                                'pos_card_checkout',
                                'payment_terminal_operation',
                                'payment_terminal_event',
                                'payment_terminal_receipt',
                                'payment_terminal_reconciliation_batch',
                                'payment_terminal_reconciliation_event',
                                'payment_terminal_secret_reference',
                                'sale_payment_session',
                                'sale_payment_allocation')
                            order by table_name
                            """.formatted(schema))) {
                    var found = new java.util.ArrayList<String>();
                    while (tables.next()) {
                        found.add(tables.getString(1));
                    }
                    assertThat(found).hasSize(10);
                }
            }
        } finally {
            try (var connection = DriverManager.getConnection(database.url(), database.user(), database.password());
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

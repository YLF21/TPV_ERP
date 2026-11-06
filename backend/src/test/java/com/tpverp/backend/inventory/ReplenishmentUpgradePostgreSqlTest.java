package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Runs only against an explicitly named disposable clone, never the store database. */
@EnabledIfEnvironmentVariable(named = "TPV_REPLENISHMENT_UPGRADE_DB_URL", matches = ".+")
class ReplenishmentUpgradePostgreSqlTest {
    @Test
    void upgradingAnExistingV285ClonePreservesEveryExistingTableAndMigration() throws Exception {
        String url = System.getenv("TPV_REPLENISHMENT_UPGRADE_DB_URL");
        assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:(?!5432/)[0-9]+/replenishment_upgrade_test");
        String user = System.getenv("TPV_ERP_TEST_DB_USER");
        String password = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
        try (var connection = DriverManager.getConnection(url,user,password)) {
            assertThat(scalar(connection,"select max(version::int)::text from flyway_schema_history where success")).isEqualTo("285");
            var before = snapshot(connection);
            String history = scalar(connection,"select md5(string_agg(row_to_json(h)::text, '' order by installed_rank)) from flyway_schema_history h");
            var flyway = FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                    .dataSource(url,user,password).schemas("public").defaultSchema("public").target("286").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
            var after = snapshot(connection);
            assertThat(after.remove("replenishment_request")).startsWith("0:");
            assertThat(after).isEqualTo(before);
            assertThat(scalar(connection,"select md5(string_agg(row_to_json(h)::text, '' order by installed_rank)) from flyway_schema_history h where version::int <= 285"))
                    .isEqualTo(history);
            assertThat(scalar(connection,"select max(version::int)::text from flyway_schema_history where success")).isEqualTo("286");
        }
    }

    private Map<String,String> snapshot(Connection connection) throws Exception {
        var tables = new java.util.ArrayList<String>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery("select table_name from information_schema.tables where table_schema='public' and table_type='BASE TABLE' and table_name <> 'flyway_schema_history' order by table_name")) {
            while (result.next()) tables.add(result.getString(1));
        }
        var snapshot = new LinkedHashMap<String,String>();
        for (String table : tables) {
            String quoted = '"' + table.replace("\"","\"\"") + '"';
            snapshot.put(table,scalar(connection,"select count(*)::text || ':' || md5(coalesce(string_agg(md5(row_to_json(t)::text), '' order by md5(row_to_json(t)::text)), '')) from " + quoted + " t"));
        }
        return snapshot;
    }

    private String scalar(Connection connection,String sql) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next(); return result.getString(1);
        }
    }
}

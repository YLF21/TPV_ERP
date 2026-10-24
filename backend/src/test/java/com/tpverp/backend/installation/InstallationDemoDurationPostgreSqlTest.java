package com.tpverp.backend.installation;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Uses only an explicitly supplied disposable database; every fixture is rolled back. */
class InstallationDemoDurationPostgreSqlTest {
    @Test void firstBootDatesCrossDstWithExactly720HoursInEverySessionTimezone() throws Exception {
        withDatabase(db -> {
            zone(db, "Atlantic/Canary");
            var autumn = new Installation("DST", "public-test-key", Instant.parse("2026-10-24T15:16:19.123456Z"));
            rejects(db, () -> insert(db, autumn), "23514"); // Reproduces the original V1 failure.
            migrate(db);
            for (String timezone : new String[]{"Atlantic/Canary", "Europe/Madrid", "Australia/Lord_Howe", "UTC"}) {
                zone(db, timezone);
                for (String date : new String[]{"2026-03-10T10:00:00.123456Z", "2026-10-03T10:00:00.123456Z"}) {
                    var installation = new Installation("DST", "public-test-key", Instant.parse(date));
                    insert(db, installation);
                    try (var statement = db.createStatement(); var row = statement.executeQuery(
                            "select extract(epoch from demo_hasta-creada_en) from instalacion")) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getLong(1)).isEqualTo(720L * 3600);
                    }
                    execute(db, "delete from instalacion");
                }
            }
        });
    }

    @Test void migrationPreservesLegacyDatesAndUnrelatedOrNoOpUpdates() throws Exception {
        withDatabase(db -> {
            zone(db, "Atlantic/Canary");
            execute(db, "insert into instalacion(id,referencia,public_key,creada_en,demo_hasta) values ('"
                    + UUID.randomUUID() + "','LEGACY','public-test-key','2026-10-24 10:00:00+00',"
                    + "'2026-10-24 10:00:00+00'::timestamptz + interval '30 days')");
            String before = dates(db);
            migrate(db);
            assertThat(dates(db)).isEqualTo(before);
            execute(db, "update instalacion set version=version+1");
            execute(db, "update instalacion set creada_en=creada_en,demo_hasta=demo_hasta");
            assertThat(dates(db)).isEqualTo(before);
            rejects(db, () -> execute(db, "update instalacion set demo_hasta=demo_hasta+interval '1 second'"), "23514");
            execute(db, "update instalacion set demo_hasta=creada_en+interval '720 hours'");
            assertThat(dates(db)).isNotEqualTo(before);
        });
    }

    @Test void rejectsChangedOrNewInvalidDatesAndRetainsNotNullAndSingletonConstraints() throws Exception {
        withDatabase(db -> {
            migrate(db);
            var installation = new Installation("VALID", "public-test-key", Instant.parse("2026-10-24T10:00:00Z"));
            insert(db, installation);
            rejects(db, () -> execute(db, "update instalacion set demo_hasta=demo_hasta-interval '1 hour'"), "23514");
            rejects(db, () -> execute(db, "update instalacion set creada_en='infinity',demo_hasta='infinity'"), "23514");
            rejects(db, () -> execute(db, "update instalacion set demo_hasta=null"), "23502");
            rejects(db, () -> insert(db, new Installation("SECOND", "public-test-key", installation.getCreadaEn())), "23505");
            execute(db, "delete from instalacion");
            rejects(db, () -> execute(db, "insert into instalacion(id,referencia,public_key,creada_en,demo_hasta) values ('"
                    + UUID.randomUUID() + "','INVALID','public-test-key','2026-10-24 10:00:00+00','2026-10-24 10:00:00+00')"), "23514");
        });
    }

    private static void withDatabase(DatabaseAction action) throws Exception {
        String url = System.getenv("TPV_DEMO_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "An explicitly supplied disposable PostgreSQL database is required");
        try (var db = DriverManager.getConnection(url, System.getenv("TPV_DEMO_TEST_DB_USER"),
                System.getenv("TPV_DEMO_TEST_DB_PASSWORD"))) {
            db.setAutoCommit(false);
            try {
                String schema = "demo_duration_" + UUID.randomUUID().toString().replace("-", "");
                execute(db, "create schema " + schema);
                execute(db, "set local search_path to " + schema);
                String original = resource("/db/migration/V1__esquema_inicial.sql");
                execute(db, original.substring(0, original.indexOf("create table empresa")));
                action.run(db);
            } finally { db.rollback(); }
        }
    }

    private static void migrate(Connection db) throws Exception {
        execute(db, resource("/db/migration/V279__installation_demo_elapsed_duration.sql"));
    }

    private static String resource(String name) throws Exception {
        try (var input = InstallationDemoDurationPostgreSqlTest.class.getResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("Missing migration " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void insert(Connection db, Installation installation) throws SQLException {
        try (var statement = db.prepareStatement(
                "insert into instalacion(id,referencia,public_key,creada_en,demo_hasta) values (?,?,?,?,?)")) {
            statement.setObject(1, installation.getId());
            statement.setString(2, installation.getReferencia());
            statement.setString(3, installation.getPublicKey());
            statement.setObject(4, OffsetDateTime.ofInstant(installation.getCreadaEn(), ZoneOffset.UTC));
            statement.setObject(5, OffsetDateTime.ofInstant(installation.getDemoHasta(), ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private static void zone(Connection db, String zone) throws SQLException {
        try (var statement = db.prepareStatement("select set_config('TimeZone',?,true)")) {
            statement.setString(1, zone);
            statement.execute();
        }
    }

    private static String dates(Connection db) throws SQLException {
        try (var statement = db.createStatement(); var row = statement.executeQuery(
                "select extract(epoch from creada_en)::text || '/' || extract(epoch from demo_hasta)::text from instalacion")) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private static void execute(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement()) { statement.execute(sql); }
    }

    private static void rejects(Connection db, SqlAction action, String state) throws Exception {
        var savepoint = db.setSavepoint();
        try {
            assertThatThrownBy(action::run).isInstanceOfSatisfying(SQLException.class,
                    failure -> assertThat(failure.getSQLState()).isEqualTo(state));
        } finally {
            db.rollback(savepoint);
            db.releaseSavepoint(savepoint);
        }
    }

    @FunctionalInterface private interface DatabaseAction { void run(Connection db) throws Exception; }
    @FunctionalInterface private interface SqlAction { void run() throws Exception; }
}

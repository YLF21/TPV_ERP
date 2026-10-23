package com.tpverp.backend.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.backup.application.TransferBackupCatalogFinalizer;
import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Opt-in disposable PostgreSQL database; no application database or credentials are inferred. */
class BackupCataloguePostgreSqlTest {

    @TempDir Path temporary;

    @Test
    void upgradePreservesIdsAndHibernateReadsGeneratedSequenceThenFinalizesOnlyItsTransfer() throws Exception {
        var database = Database.resolve();
        String schema = schema("backup_catalogue_");
        UUID installation = UUID.randomUUID();
        UUID configuration = UUID.randomUUID();
        UUID older = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID newer = UUID.fromString("00000000-0000-0000-0000-000000000012");
        try {
            migrate(database, schema, "273");
            try (var db = database.open(schema); var sql = db.createStatement()) {
                sql.execute("insert into instalacion(id,referencia,public_key,creada_en,demo_hasta) values ('"
                        + installation + "','CATALOGUE-TEST','test-public-key',now(),now()+interval '30 days')");
                sql.execute("insert into configuracion_backup(id,instalacion_id,destino) values ('"
                        + configuration + "','" + installation + "','{\"path\":\"old-source-directory\"}')");
                legacy(db, newer, configuration, "2025-02-01T12:00:00Z", "FALLO");
                legacy(db, older, configuration, "2025-01-01T12:00:00Z", "EXITO");
            }

            migrate(database, schema, "276");
            try (var db = database.open(schema)) {
                assertThat(sequences(db)).containsExactly(Map.entry(older, 1L), Map.entry(newer, 2L));
                assertThat(number(db, "select count(*) from ejecucion_backup where origin='LEGACY'"))
                        .as("Historical rows must not invent a manual or automatic origin").isEqualTo(2);
                assertThat(text(db, "select phase from ejecucion_backup where id='" + older + "'"))
                        .isEqualTo("COMPLETED");
                assertThat(text(db, "select phase from ejecucion_backup where id='" + newer + "'"))
                        .isEqualTo("FAILED");
                execute(db, "delete from ejecucion_backup where id='" + newer + "'");
                assertThat(number(db, "select backup_sequence from configuracion_backup")).isEqualTo(2);
            }

            UUID transferExecution = persistUsingHibernate(database, schema, configuration, 3L);
            UUID transfer = UUID.randomUUID();
            try (var db = database.open(schema)) {
                execute(db, "insert into server_transfer(id,installation_id,state,destination_public_key,destination_key_fingerprint)"
                        + " values ('" + transfer + "','" + installation + "','PREPARED','test-destination','" + "a".repeat(64) + "')");
                execute(db, "update server_transfer set state='FROZEN' where id='" + transfer + "'");
                // A qualified write cannot evade the guard by changing the caller's search_path.
                execute(db, "set search_path to public");
                assertThatThrownBy(() -> execute(db, "update " + schema + ".instalacion set referencia=referencia"))
                        .isInstanceOf(SQLException.class)
                        .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("55000"));
                db.setSchema(schema);

                Path received = Files.write(temporary.resolve("received.tpvb"), new byte[] {3, 1, 4, 1, 5});
                Path destination = temporary.resolve("destination");
                var mapper = new ObjectMapper();
                var manifest = mapper.valueToTree(Map.of("schemaVersion", 1, "executionId", transferExecution.toString(),
                        "sequenceNumber", 3L, "origin", "TRANSFER", "installationId", installation.toString()));
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(received)));
                String unrelatedBefore = text(db, "select (to_jsonb(e)-'metadata')::text from ejecucion_backup e where id='" + older + "'");
                execute(db, "insert into cloud_backup_delivery(backup_id,installation_id,descriptor,spool_sha256,state,lease_token,lease_until) values ('"
                        + older + "','" + installation + "','{}','" + "a".repeat(64) + "','UPLOADING','" + UUID.randomUUID() + "',now()+interval '2 minutes')");
                db.setAutoCommit(false);
                maintenance(db, transfer);
                TransferBackupCatalogFinalizer.finalizeReceived(db, manifest, received, destination, digest);
                db.commit();
                Path catalogueFile = destination.resolve("daily/000003.tpvb");
                assertThat(Files.readAllBytes(catalogueFile)).containsExactly(Files.readAllBytes(received));
                assertThat(text(db, "select result || ':' || phase from ejecucion_backup where id='" + transferExecution + "'"))
                        .isEqualTo("EXITO:COMPLETED");
                assertThat(text(db, "select metadata->>'path' from ejecucion_backup where id='" + transferExecution + "'"))
                        .isEqualTo(catalogueFile.toAbsolutePath().normalize().toString());
                assertThat(text(db, "select destino->>'path' from configuracion_backup where id='" + configuration + "'"))
                        .isEqualTo(destination.toAbsolutePath().normalize().toString());
                assertThat(text(db, "select (to_jsonb(e)-'metadata')::text from ejecucion_backup e where id='" + older + "'"))
                        .isEqualTo(unrelatedBefore);
                assertThat(text(db, "select metadata->>'restoreSourceUnavailable' from ejecucion_backup where id='" + older + "'"))
                        .isEqualTo("true");
                assertThat(text(db, "select metadata->>'cloudAlreadyStored' from ejecucion_backup where id='" + transferExecution + "'"))
                        .isEqualTo("true");
                assertThat(text(db, "select state from cloud_backup_delivery where backup_id='" + older + "'"))
                        .isEqualTo("RETIRED");
                assertThat(number(db, "select count(*) from cloud_backup_delivery where lease_token is not null or lease_until is not null"))
                        .isZero();
                assertThat(number(db, "select backup_sequence from configuracion_backup")).isEqualTo(3);

                maintenance(db, transfer);
                // Repeat with the four-argument API: the existing identical file must remain usable.
                TransferBackupCatalogFinalizer.finalizeReceived(db, manifest, received, destination);
                db.commit();
                assertThat(Files.readAllBytes(catalogueFile)).containsExactly(Files.readAllBytes(received));
                assertThat(number(db, "select count(*) from ejecucion_backup")).isEqualTo(2);

                Path conflictDirectory = temporary.resolve("conflict");
                Files.createDirectories(conflictDirectory.resolve("daily"));
                Path conflict = Files.write(conflictDirectory.resolve("daily/000003.tpvb"), new byte[] {9, 8, 7});
                assertThatThrownBy(() -> TransferBackupCatalogFinalizer.finalizeReceived(
                        db, manifest, received, conflictDirectory, digest)).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("mismo ID");
                db.rollback();
                assertThat(Files.readAllBytes(conflict)).containsExactly((byte) 9, (byte) 8, (byte) 7);
                assertThat(text(db, "select destino->>'path' from configuracion_backup"))
                        .isEqualTo(destination.toAbsolutePath().normalize().toString());

                var wrongExecution = manifest.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) wrongExecution).put("executionId", older.toString());
                assertThatThrownBy(() -> TransferBackupCatalogFinalizer.finalizeReceived(
                        db, wrongExecution, received, destination, digest)).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("catálogo restaurado");
                db.rollback();
                assertThat(text(db, "select (to_jsonb(e)-'metadata')::text from ejecucion_backup e where id='" + older + "'"))
                        .isEqualTo(unrelatedBefore);

                maintenance(db, transfer);
                execute(db, "update server_transfer set state='ACTIVE' where id='" + transfer + "'");
                execute(db, "delete from ejecucion_backup where id='" + transferExecution + "'");
                db.commit();
            }
            // Deleting the highest historical row must not recycle the last issued number.
            persistUsingHibernate(database, schema, configuration, 4L);
        } finally {
            database.drop(schema);
        }
    }

    @Test
    void v274BackfillIsDeterministicAndIndependentPerConfiguration() throws Exception {
        var database = Database.resolve();
        String schema = schema("backup_sequence_");
        UUID firstConfiguration = UUID.randomUUID();
        UUID secondConfiguration = UUID.randomUUID();
        UUID earlierId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID laterId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID secondId = UUID.randomUUID();
        try (var db = database.open(null)) {
            execute(db, "create schema " + schema);
            db.setSchema(schema);
            // Focused migration fixture: production has one configuration per singleton installation;
            // multiple configurations here exercise the migration's partitioning independently.
            execute(db, "create table configuracion_backup(id uuid primary key)");
            execute(db, "create table ejecucion_backup(id uuid primary key, configuracion_id uuid references configuracion_backup(id),"
                    + " iniciada_en timestamptz not null, result text not null)");
            execute(db, "insert into configuracion_backup values ('" + firstConfiguration + "'),('" + secondConfiguration + "')");
            execute(db, "insert into ejecucion_backup values ('" + laterId + "','" + firstConfiguration + "','2025-01-01','EXITO'),"
                    + "('" + earlierId + "','" + firstConfiguration + "','2025-01-01','FALLO'),"
                    + "('" + secondId + "','" + secondConfiguration + "','2025-02-01','EXITO')");
            try (var migration = getClass().getResourceAsStream("/db/migration/V274__backup_catalogue.sql")) {
                assertThat(migration).isNotNull();
                execute(db, new String(migration.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertThat(number(db, "select sequence_number from ejecucion_backup where id='" + earlierId + "'")).isEqualTo(1);
            assertThat(number(db, "select sequence_number from ejecucion_backup where id='" + laterId + "'")).isEqualTo(2);
            assertThat(number(db, "select sequence_number from ejecucion_backup where id='" + secondId + "'")).isEqualTo(1);
            assertThat(number(db, "select count(*) from ejecucion_backup where origin='LEGACY'")).isEqualTo(3);
            execute(db, "delete from ejecucion_backup where configuracion_id='" + firstConfiguration + "'");
            UUID nextFirst = UUID.randomUUID();
            UUID nextSecond = UUID.randomUUID();
            execute(db, "insert into ejecucion_backup(id,configuracion_id,iniciada_en,result) values ('" + nextFirst + "','"
                    + firstConfiguration + "',now(),'EXITO'),('" + nextSecond + "','" + secondConfiguration + "',now(),'EXITO')");
            assertThat(number(db, "select sequence_number from ejecucion_backup where id='" + nextFirst + "'")).isEqualTo(3);
            assertThat(number(db, "select sequence_number from ejecucion_backup where id='" + nextSecond + "'")).isEqualTo(2);
            assertThat(text(db, "select origin from ejecucion_backup where id='" + nextFirst + "'")).isEqualTo("MANUAL");
            assertThat(text(db, "select origin from ejecucion_backup where id='" + nextSecond + "'")).isEqualTo("MANUAL");
            assertThat(number(db, "select sequence_number from ejecucion_backup where id='" + secondId + "'")).isEqualTo(1);
            db.setSchema("public");
            UUID qualifiedInsert = UUID.randomUUID();
            execute(db, "insert into " + schema + ".ejecucion_backup(id,configuracion_id,iniciada_en,result) values ('"
                    + qualifiedInsert + "','" + firstConfiguration + "',now(),'EXITO')");
            assertThat(number(db, "select sequence_number from " + schema + ".ejecucion_backup where id='" + qualifiedInsert + "'"))
                    .isEqualTo(4);
        } finally {
            database.drop(schema);
        }
    }

    private static UUID persistUsingHibernate(Database database, String schema, UUID configuration, long expected) {
        var dataSource = new DriverManagerDataSource(database.url() + (database.url().contains("?") ? "&" : "?")
                + "currentSchema=" + schema, database.user(), database.password());
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setManagedTypes(PersistenceManagedTypes.of(Installation.class.getName(), BackupSettings.class.getName(), BackupExecution.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        factory.afterPropertiesSet();
        try (var entityManager = factory.getObject().createEntityManager()) {
            entityManager.getTransaction().begin();
            try {
                Instant now = Instant.now();
                var execution = new BackupExecution(entityManager.getReference(BackupSettings.class, configuration), now,
                        UUID.randomUUID(), now, now.plusSeconds(120));
                execution.setOrigin("TRANSFER");
                entityManager.persist(execution);
                entityManager.flush();
                assertThat(execution.getSequenceNumber()).as("@Generated must fetch PostgreSQL trigger value after flush")
                        .isEqualTo(expected);
                entityManager.getTransaction().commit();
                return execution.getId();
            } finally {
                if (entityManager.getTransaction().isActive()) entityManager.getTransaction().rollback();
            }
        } finally {
            factory.destroy();
        }
    }

    private static void legacy(Connection db, UUID id, UUID configuration, String instant, String result) throws SQLException {
        execute(db, "insert into ejecucion_backup(id,configuracion_id,iniciada_en,finalizada_en,result,metadata,error_reason) values ('"
                + id + "','" + configuration + "','" + instant + "','" + instant + "','" + result
                + "','{\"legacy\":true}'," + (result.equals("FALLO") ? "'legacy failure'" : "null") + ")");
    }

    private static Map<UUID, Long> sequences(Connection db) throws SQLException {
        var result = new LinkedHashMap<UUID, Long>();
        try (var statement = db.createStatement(); var rows = statement.executeQuery("select id,sequence_number from ejecucion_backup order by sequence_number")) {
            while (rows.next()) result.put(rows.getObject(1, UUID.class), rows.getLong(2));
        }
        return result;
    }

    private static void maintenance(Connection db, UUID transfer) throws SQLException {
        try (var statement = db.prepareStatement("select set_config('tpv.transfer.maintenance_id',?,true)")) {
            statement.setString(1, transfer.toString());
            statement.execute();
        }
    }

    private static String text(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static long number(Connection db, String sql) throws SQLException { return Long.parseLong(text(db, sql)); }
    private static void execute(Connection db, String sql) throws SQLException { try (var statement = db.createStatement()) { statement.execute(sql); } }
    private static String schema(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", ""); }

    private static void migrate(Database database, String schema, String target) {
        FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                .dataSource(database.url(), database.user(), database.password()).schemas(schema).defaultSchema(schema)
                .createSchemas(true).target(target).load().migrate();
    }

    private record Database(String url, String user, String password) {
        static Database resolve() {
            String url = System.getenv("TPV_BACKUP_TEST_DB_URL");
            assumeTrue(url != null && !url.isBlank(), "Configure a disposable TPV_BACKUP_TEST_DB_URL");
            return new Database(url, System.getenv().getOrDefault("TPV_BACKUP_TEST_DB_USER", "postgres"),
                    System.getenv().getOrDefault("TPV_BACKUP_TEST_DB_PASSWORD", ""));
        }
        Connection open(String schema) throws SQLException {
            var connection = DriverManager.getConnection(url, user, password);
            if (schema != null) connection.setSchema(schema);
            return connection;
        }
        void drop(String schema) throws SQLException { try (var db = open(null)) { execute(db, "drop schema if exists " + schema + " cascade"); } }
    }
}

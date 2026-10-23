package com.tpverp.backend.backup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Opt-in, dedicated disposable database only; never defaults to the application's database. */
class BackupSnapshotServicePostgreSqlTest {

    @TempDir Path directory;

    @Test
    @SuppressWarnings("unchecked")
    void dumpAndManifestExcludeConcurrentCommitAndCountPartitionsExactlyOnce() throws Exception {
        String url = System.getenv("TPV_BACKUP_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "Configure a disposable TPV_BACKUP_TEST_DB_URL");
        String user = System.getenv().getOrDefault("TPV_BACKUP_TEST_DB_USER", "postgres");
        String password = System.getenv().getOrDefault("TPV_BACKUP_TEST_DB_PASSWORD", "");
        String schema = "backup_snapshot_" + UUID.randomUUID().toString().replace("-", "");
        var database = new DriverManagerDataSource(url, user, password);
        var snapshotDatabase = new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        var initialProduct = UUID.randomUUID();
        var concurrentProduct = UUID.randomUUID();
        var store = UUID.randomUUID();
        var firstTicket = UUID.randomUUID();
        var lastTicket = UUID.randomUUID();
        var installation = UUID.randomUUID();
        try {
            try (var connection = database.getConnection(); var statement = connection.createStatement()) {
                statement.execute("create schema " + schema);
                statement.execute("set search_path to " + schema);
                for (String table : List.of("producto", "cliente", "miembro", "proveedor", "documento_linea",
                        "documento_pago", "existencia", "movimiento_stock", "sesion_caja", "movimiento_caja",
                        "usuario", "terminal")) {
                    statement.execute("create table " + table + " (id uuid primary key)");
                }
                statement.execute("create table tienda (id uuid primary key, codigo_tienda varchar(3))");
                statement.execute("create table documento (id uuid primary key, tienda_id uuid, tipo text, numero text,"
                        + " fecha date, creado_en timestamptz, confirmado_en timestamptz)");
                statement.execute("create table contador_documento (tienda_id uuid, tipo text, periodo text, ultimo_numero integer)");
                statement.execute("create table cadena_fiscal (id uuid, empresa_id uuid, instalacion_id uuid, ultimo_registro_id uuid, ultima_secuencia bigint, ultima_huella text, version bigint)");
                statement.execute("create table cadena_eventos_fiscal (id uuid, empresa_id uuid, instalacion_id uuid, ultima_secuencia bigint, ultima_huella text, version bigint)");
                statement.execute("insert into cadena_fiscal values ('"+UUID.randomUUID()+"','"+UUID.randomUUID()+"','"+installation+"',null,0,null,0)");
                statement.execute("insert into producto values ('" + initialProduct + "')");
                statement.execute("insert into tienda values ('" + store + "', '001')");
                statement.execute("insert into documento values ('" + firstTicket + "','" + store
                        + "','TICKET','001-261022-00001','2026-10-22',now(),now()), ('" + lastTicket + "','" + store
                        + "','TICKET','001-261022-00002','2026-10-22',now(),now())");
                statement.execute("insert into contador_documento values ('" + store + "','T','20261022',2)");
                statement.execute("create table partitioned (id integer) partition by range (id)");
                statement.execute("create table partition_child partition of partitioned for values from (0) to (10)");
                statement.execute("insert into partitioned values (1),(2),(3)");
                statement.execute("create table \"odd\"\"name\" (id integer)");
                statement.execute("insert into \"odd\"\"name\" values (1),(2)");
            }
            var realCommands = new PostgreSqlBackupCommands(url, user, password, command("pg_dump"), command("pg_restore"));
            var commands = mock(PostgreSqlBackupCommands.class);
            doAnswer(invocation -> {
                // This commit happens after capture's snapshot, but before the real pg_dump starts.
                try (var connection = snapshotDatabase.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("insert into producto values ('" + concurrentProduct + "')");
                }
                realCommands.dump(invocation.getArgument(0, Path.class), invocation.getArgument(1, String.class));
                return null;
            }).when(commands).dump(any(Path.class), any(String.class));
            var service = new BackupSnapshotService(snapshotDatabase,
                    Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC));
            Path dump = directory.resolve("database.dump");
            try (var capture = service.capture(commands, dump, installation, "test-version")) {
                var manifest = capture.manifest();
                assertThat(manifest).containsEntry("schemaVersion", 1)
                        .containsEntry("installationId", installation.toString())
                        .containsEntry("applicationVersion", "test-version");
                assertThat(Instant.parse((String) manifest.get("capturedAt")))
                        .isAfter(Instant.parse("2020-01-01T00:00:00Z"));
                assertThat((Map<String, Long>) manifest.get("categories"))
                        .containsEntry("products", 1L).containsEntry("documents", 2L);
                assertThat((Map<String, Long>) manifest.get("documentsByType")).containsEntry("TICKET", 2L);
                var lastDocuments = (List<Map<String, Object>>) manifest.get("lastDocuments");
                assertThat(lastDocuments).hasSize(1);
                assertThat(lastDocuments.getFirst()).containsEntry("documentId", lastTicket.toString())
                        .containsEntry("number", "001-261022-00002").containsEntry("period", "20261022")
                        .containsEntry("series", "001-261022").containsEntry("storeId", store.toString());
                assertThat((Map<String, Long>) manifest.get("tableCounts"))
                        .containsEntry('"' + schema + "\".\"partitioned\"", 0L)
                        .containsEntry('"' + schema + "\".\"partition_child\"", 3L)
                        .containsEntry('"' + schema + "\".\"odd\"\"name\"", 2L);
                Path sql = directory.resolve("restored-data.sql");
                var process = new ProcessBuilder(command("pg_restore"), "--data-only", "--file=" + sql, dump.toString())
                        .redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertThat(process.waitFor()).as(output).isZero();
                assertThat(Files.readString(sql)).contains(initialProduct.toString()).doesNotContain(concurrentProduct.toString());
                var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
                var archived=mapper.readTree(mapper.writeValueAsBytes(manifest));
                try(var restored=snapshotDatabase.getConnection();var statement=restored.createStatement()) {
                    statement.execute("delete from producto where id='"+concurrentProduct+"'");
                    BackupSnapshotService.verifyRestoredSnapshot(restored,archived);
                    statement.execute("update contador_documento set ultimo_numero=3");
                    assertThatThrownBy(()->BackupSnapshotService.verifyRestoredSnapshot(restored,archived))
                            .hasMessageContaining("documentCounters");
                    statement.execute("update contador_documento set ultimo_numero=2");
                    statement.execute("update documento set numero='001-261022-00003' where id='"+lastTicket+"'");
                    assertThatThrownBy(()->BackupSnapshotService.verifyRestoredSnapshot(restored,archived))
                            .hasMessageContaining("lastDocuments");
                    statement.execute("update documento set numero='001-261022-00002' where id='"+lastTicket+"'");
                    statement.execute("update cadena_fiscal set ultima_huella=repeat('A',64),ultima_secuencia=1,version=1");
                    assertThatThrownBy(()->BackupSnapshotService.verifyRestoredSnapshot(restored,archived))
                            .hasMessageContaining("fiscalChainHeads");
                    statement.execute("update cadena_fiscal set ultima_huella=null,ultima_secuencia=0,version=0");
                    statement.execute("create table unexpected_empty_table (id uuid)");
                    assertThatThrownBy(()->BackupSnapshotService.verifyRestoredSnapshot(restored,archived))
                            .hasMessageContaining("tableCounts");
                    statement.execute("drop table unexpected_empty_table");
                    BackupSnapshotService.verifyRestoredSnapshot(restored,archived);
                }
            }
            // A failure must release both the exporter connection and the filesystem guard.
            var failingCommands = mock(PostgreSqlBackupCommands.class);
            doAnswer(invocation -> { throw new IllegalStateException("simulated dump failure"); })
                    .when(failingCommands).dump(any(Path.class), any(String.class));
            assertThatThrownBy(() -> service.capture(failingCommands, directory.resolve("failed.dump"), installation, "test"))
                    .hasMessage("simulated dump failure");
            try (var executor = Executors.newSingleThreadExecutor()) {
                assertThat(executor.submit(() -> {
                    try (var mutation = SharedFileSnapshotGuard.fileMutation()) {
                        return true;
                    }
                }).get(5, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            try (Connection connection = database.getConnection(); var statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static String command(String executable) {
        String bin = System.getenv("TPV_BACKUP_TEST_PG_BIN");
        return bin == null ? executable : Path.of(bin, executable
                + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "")).toString();
    }
}

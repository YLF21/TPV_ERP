package com.tpverp.backend.backup.cloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import com.tpverp.backend.servertransfer.ServerTransferFence;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Opt-in disposable PostgreSQL schema and loopback HTTP fixture; never uses a real SaaS. */
class CloudBackupQueuePostgreSqlTest {
    @TempDir Path temporary;

    @Test void durableRetryExpiredLeaseAndTransferFence() throws Exception {
        String url = System.getenv("TPV_BACKUP_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "Configure a disposable TPV_BACKUP_TEST_DB_URL");
        String user = System.getenv().getOrDefault("TPV_BACKUP_TEST_DB_USER", "postgres");
        String password = System.getenv().getOrDefault("TPV_BACKUP_TEST_DB_PASSWORD", "");
        String schema = "cloud_queue_" + UUID.randomUUID().toString().replace("-", "");
        var flyway = FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                .dataSource(url, user, password).schemas(schema).defaultSchema(schema).createSchemas(true)
                .target("276").load();
        flyway.migrate();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            var database = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                    + "currentSchema=" + schema, user, password);
            var jdbc = new JdbcTemplate(database);
            UUID installation = UUID.randomUUID();
            UUID configuration = UUID.randomUUID();
            jdbc.update("insert into instalacion(id,referencia,public_key,creada_en,demo_hasta) values (?,?,'test',now(),now()+interval '30 days')",
                    installation, "CLOUD-TEST");
            jdbc.update("insert into configuracion_backup(id,instalacion_id,destino) values (?,?,cast('{}' as jsonb))",
                    configuration, installation);
            var current = new java.util.concurrent.atomic.AtomicReference<UUID>();
            var available = new AtomicBoolean(false);
            var outage = new AtomicBoolean(true);
            var acknowledgementUnavailable = new AtomicBoolean(false);
            server.createContext("/api/v1/backup-vault/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                int code = outage.get() ? 503 : 200;
                if (path.endsWith("/status") && available.get() && acknowledgementUnavailable.get()) code = 500;
                byte[] body;
                if (path.endsWith("/content")) {
                    byte[] uploaded = exchange.getRequestBody().readAllBytes();
                    assertThat(uploaded).isEqualTo("encrypted-backup".getBytes(StandardCharsets.UTF_8));
                    available.set(true);
                    body = "{\"state\":\"AVAILABLE\"}".getBytes(StandardCharsets.UTF_8);
                } else if (path.endsWith("/status")) {
                    body = ("{\"state\":\"" + (available.get() ? "AVAILABLE" : "PENDING") + "\"}")
                            .getBytes(StandardCharsets.UTF_8);
                } else if (path.endsWith("/available")) {
                    body = (available.get() ? "[\"" + current.get() + "\"]" : "[]")
                            .getBytes(StandardCharsets.UTF_8);
                } else body = "{\"state\":\"PENDING\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
            var credentials = mock(LicenseSaasCredentialStore.class);
            when(credentials.readToken()).thenReturn(Optional.of("test-token"));
            var fence = mock(ServerTransferFence.class);
            var frozen = new AtomicBoolean(false);
            when(fence.frozen()).thenAnswer(invocation -> frozen.get());
            var queue = new CloudBackupQueue(jdbc, new ObjectMapper().findAndRegisterModules(), credentials,
                    fence, temporary.resolve("spool"), "http://127.0.0.1:" + server.getAddress().getPort());
            byte[] data = "encrypted-backup".getBytes(StandardCharsets.UTF_8);
            byte[] key = "wrapped-recovery-v2".getBytes(StandardCharsets.UTF_8);
            Path file = Files.write(temporary.resolve("source.tpvb"), data);

            UUID first = UUID.randomUUID();
            current.set(first);
            execution(jdbc, first, configuration);
            var firstDescriptor = descriptor(first, installation, 1, data, key);
            queue.enqueue(firstDescriptor, file, key);
            queue.deliverDue();
            assertThat(queue.status(first).state()).isEqualTo("FAILED");
            assertThat(Files.isRegularFile(temporary.resolve("spool").resolve(first + ".tpvb"))).isTrue();
            outage.set(false);
            acknowledgementUnavailable.set(true);
            queue.retry(first);
            queue.deliverDue();
            assertThat(queue.status(first).state()).isEqualTo("FAILED");
            assertThat(available.get()).isTrue();
            assertThat(Files.isRegularFile(temporary.resolve("spool").resolve(first + ".tpvb"))).isTrue();
            acknowledgementUnavailable.set(false);
            queue.retry(first);
            queue.deliverDue();
            assertThat(queue.status(first).state()).isEqualTo("SENT");
            assertThat(queue.status(first).attempts()).isEqualTo(3);
            assertThat(queue.statuses(List.of(first))).containsKey(first);
            assertThat(Files.exists(temporary.resolve("spool").resolve(first + ".tpvb"))).isFalse();
            queue.enqueue(firstDescriptor, file, key);
            assertThat(queue.status(first).state()).isEqualTo("SENT");
            assertThat(Files.exists(temporary.resolve("spool").resolve(first + ".tpvb"))).isFalse();
            assertThat(Files.exists(temporary.resolve("spool").resolve(first + ".recovery-v2"))).isFalse();

            UUID second = UUID.randomUUID();
            current.set(second); available.set(false);
            execution(jdbc, second, configuration);
            queue.enqueue(descriptor(second, installation, 2, data, key), file, key);
            jdbc.update("update cloud_backup_delivery set state='UPLOADING',lease_token=?,lease_until=now()-interval '1 minute' where backup_id=?",
                    UUID.randomUUID(), second);
            frozen.set(true);
            queue.deliverDue();
            assertThat(queue.status(second).state()).isEqualTo("UPLOADING");
            assertThat(queue.runOneForTransfer(second)).isTrue();
            assertThat(queue.status(second).state()).isEqualTo("SENT");
            assertThat(queue.status(first).state()).isEqualTo("RETIRED");

            UUID third = UUID.randomUUID();
            current.set(third); available.set(false);
            execution(jdbc, third, configuration);
            queue.enqueue(descriptor(third, installation, 3, data, key), file, key);
            assertThat(queue.runAllForTransfer()).isTrue();
            assertThat(queue.status(third).state()).isEqualTo("SENT");
            assertThat(queue.status(second).state()).isEqualTo("RETIRED");
            assertThat(queue.runOneForTransfer(second)).isTrue();

            UUID fourth = UUID.randomUUID();
            current.set(fourth); available.set(false); outage.set(true);
            execution(jdbc, fourth, configuration);
            queue.enqueue(descriptor(fourth, installation, 4, data, key), file, key);
            assertThat(queue.runAllForTransfer()).isFalse();
            assertThat(queue.status(fourth).state()).isEqualTo("FAILED");
            assertThat(Files.isRegularFile(temporary.resolve("spool").resolve(fourth + ".tpvb"))).isTrue();
        } finally {
            server.stop(0);
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }

    private static void execution(JdbcTemplate jdbc, UUID id, UUID configuration) {
        jdbc.update("insert into ejecucion_backup(id,configuracion_id,iniciada_en,result) values (?,?,now(),'EXITO')",
                id, configuration);
    }
    private static CloudBackupDescriptor descriptor(UUID id, UUID installation, long sequence,
            byte[] data, byte[] key) throws Exception {
        return new CloudBackupDescriptor(id, installation, sequence, Instant.now(), "test", 1, 0,
                data.length, sha256(data), sha256(key), 2);
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

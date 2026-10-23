package com.tpverp.backend.backup;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.backup.application.BackupKeyStore;
import com.tpverp.backend.backup.cloud.CloudBackupQueue;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import com.tpverp.backend.servertransfer.ServerTransferFence;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CloudBackupRepairPostgreSqlTest {
    @TempDir Path temporary;

    @Test void crashAfterLocalCommitRepairsFromMonthlyFileAndSkipsUnavailableRestoredSources() throws Exception {
        String url = System.getenv("TPV_BACKUP_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "Explicit disposable PostgreSQL required");
        String user = System.getenv().getOrDefault("TPV_BACKUP_TEST_DB_USER", "postgres");
        String password = System.getenv().getOrDefault("TPV_BACKUP_TEST_DB_PASSWORD", "");
        String schema = "cloud_repair_" + UUID.randomUUID().toString().replace("-", "");
        try (var db = DriverManager.getConnection(url, user, password); var sql = db.createStatement()) {
            sql.execute("create schema " + schema); sql.execute("set search_path to " + schema);
            try {
                sql.execute("create table ejecucion_backup(id uuid primary key, result text, metadata jsonb, sequence_number bigint, iniciada_en timestamptz)");
                try (var input = getClass().getResourceAsStream("/db/migration/V277__cloud_backup_delivery_queue.sql")) {
                    sql.execute(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
                var dataSource = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
                var jdbc = new JdbcTemplate(dataSource);
                var json = new ObjectMapper().findAndRegisterModules()
                        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
                var queue = new CloudBackupQueue(jdbc, json, mock(LicenseSaasCredentialStore.class),
                        mock(ServerTransferFence.class), temporary.resolve("protected-spool"), "http://127.0.0.1:1");
                var keys = mock(BackupKeyStore.class);
                var repair = new CloudBackupRepairService(jdbc, json, keys, queue);
                UUID id = UUID.randomUUID(), installation = UUID.randomUUID();
                byte[] wrapped = "encrypted recovery key fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Path source = Files.writeString(temporary.resolve("000051.tpvb"), "verified encrypted snapshot");
                var metadata = new LinkedHashMap<String, Object>();
                metadata.put("path", temporary.resolve("removed-daily.tpvb").toString());
                metadata.put("monthlyPath", source.toString());
                metadata.put("cloudRequired", true);
                metadata.put("cloudRecoveryKey", Base64.getEncoder().encodeToString(wrapped));
                metadata.put("manifest", Map.of("capturedAt", Instant.parse("2026-10-23T10:00:00Z").toString(),
                        "installationId", installation.toString(), "applicationVersion", "test-v277", "portableSecretsVersion", 1));
                jdbc.update("insert into ejecucion_backup values (?,'EXITO',?::jsonb,51,now())", id, json.writeValueAsString(metadata));
                for (String flag : List.of("restoreSourceUnavailable", "cloudAlreadyStored")) {
                    var unavailable = new LinkedHashMap<>(metadata); unavailable.put(flag, true);
                    unavailable.put("monthlyPath", temporary.resolve("missing.tpvb").toString());
                    jdbc.update("insert into ejecucion_backup values (?,'EXITO',?::jsonb,52,now())", UUID.randomUUID(), json.writeValueAsString(unavailable));
                }
                repair.repairMissing();
                assertThat(queue.status(id).state()).isEqualTo("PENDING");
                assertThat(Files.readAllBytes(temporary.resolve("protected-spool/" + id + ".tpvb"))).isEqualTo(Files.readAllBytes(source));
                assertThat(Files.readAllBytes(temporary.resolve("protected-spool/" + id + ".recovery-v2"))).isEqualTo(wrapped);
                assertThat(jdbc.queryForObject("select count(*) from cloud_backup_delivery", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("select descriptor->>'capturedAt' from cloud_backup_delivery", String.class)).isEqualTo("2026-10-23T10:00:00Z");
                repair.repairOne(id); repair.repairMissing();
                assertThat(repair.repairAllForTransfer()).isTrue();
                assertThat(jdbc.queryForObject("select count(*) from cloud_backup_delivery", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from ejecucion_backup where metadata ? 'cloudPreparationError'", Integer.class)).isZero();
                verifyNoInteractions(keys);
            } finally { sql.execute("drop schema " + schema + " cascade"); }
        }
    }
}

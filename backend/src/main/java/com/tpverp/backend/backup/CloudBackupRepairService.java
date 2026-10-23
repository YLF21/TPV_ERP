package com.tpverp.backend.backup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.backup.application.BackupKeyStore;
import com.tpverp.backend.backup.cloud.CloudBackupDescriptor;
import com.tpverp.backend.backup.cloud.CloudBackupQueue;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Repairs a crash between confirming a local backup and publishing its durable cloud spool. */
@Service
public class CloudBackupRepairService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final BackupKeyStore keys;
    private final CloudBackupQueue queue;
    public CloudBackupRepairService(JdbcTemplate jdbc, ObjectMapper json, BackupKeyStore keys, CloudBackupQueue queue) {
        this.jdbc = jdbc; this.json = json; this.keys = keys; this.queue = queue;
    }

    @Scheduled(fixedDelayString = "${tpv.backup.cloud-repair-delay-ms:60000}")
    public void repairMissing() {
        var ids = jdbc.query("""
                select e.id from ejecucion_backup e
                where e.result='EXITO' and e.metadata->>'cloudRequired'='true'
                  and coalesce(e.metadata->>'restoreSourceUnavailable','false') <> 'true'
                  and coalesce(e.metadata->>'cloudAlreadyStored','false') <> 'true'
                  and not exists(select 1 from cloud_backup_delivery q where q.backup_id=e.id)
                order by coalesce((e.metadata->>'cloudRepairAt')::timestamptz,'epoch'::timestamptz),e.iniciada_en
                limit 20
                """, (rows, index) -> rows.getObject(1, UUID.class));
        for (var id : ids) {
            try { repairOne(id); }
            catch (Exception failure) {
                jdbc.update("""
                        update ejecucion_backup set metadata=jsonb_set(metadata,'{cloudPreparationError}',
                          to_jsonb('CLOUD_BACKUP_QUEUING_FAILED'::text)) where id=?
                        """, id);
            }
        }
    }

    public void repairOne(UUID id) throws Exception {
        if (queue.status(id) != null) return;
        var rows = jdbc.query("""
                select metadata::text,sequence_number from ejecucion_backup
                where id=? and result='EXITO' and metadata->>'cloudRequired'='true'
                  and coalesce(metadata->>'restoreSourceUnavailable','false') <> 'true'
                  and coalesce(metadata->>'cloudAlreadyStored','false') <> 'true'
                """, (rs, index) -> new Source(rs.getString(1), rs.getLong(2)), id);
        if (rows.isEmpty()) throw new IllegalStateException("CLOUD_BACKUP_SOURCE_UNAVAILABLE");
        jdbc.update("update ejecucion_backup set metadata=jsonb_set(metadata,'{cloudRepairAt}',to_jsonb(now())) where id=?", id);
        var metadata = json.readValue(rows.getFirst().metadata(), new TypeReference<Map<String, Object>>() { });
        Path file = sourceFile(metadata);
        byte[] wrapped = metadata.get("cloudRecoveryKey") instanceof String value
                ? Base64.getDecoder().decode(value) : keys.exportWrappedRecoveryKey();
        try {
            if (wrapped.length == 0 || wrapped.length > 1024 * 1024) throw new IllegalStateException("CLOUD_BACKUP_KEY_INVALID");
            CloudBackupDescriptor descriptor;
            if (metadata.get("cloudDescriptor") instanceof Map<?, ?> existing) {
                descriptor = json.convertValue(existing, CloudBackupDescriptor.class);
            } else {
                if (!(metadata.get("manifest") instanceof Map<?, ?> manifest)) throw new IllegalStateException("CLOUD_BACKUP_MANIFEST_MISSING");
                descriptor = new CloudBackupDescriptor(id, UUID.fromString(manifest.get("installationId").toString()),
                        rows.getFirst().sequence(), Instant.parse(manifest.get("capturedAt").toString()),
                        manifest.get("applicationVersion").toString(),
                        metadata.get("formatVersion") instanceof Number format ? format.intValue() : 1,
                        manifest.get("portableSecretsVersion") instanceof Number number ? number.intValue() : 0,
                        Files.size(file), sha256(file), sha256(wrapped), 2);
            }
            if (!descriptor.backupId().equals(id)) throw new IllegalStateException("CLOUD_BACKUP_ID_MISMATCH");
            var repair = Map.of("cloudDescriptor", descriptorMetadata(descriptor),
                    "cloudRecoveryKey", Base64.getEncoder().encodeToString(wrapped));
            jdbc.update("update ejecucion_backup set metadata=(metadata || cast(? as jsonb)) - 'cloudPreparationError' where id=?",
                    json.writeValueAsString(repair), id);
            queue.enqueue(descriptor, file, wrapped);
        } finally { Arrays.fill(wrapped, (byte) 0); }
    }

    public void requestRepair(UUID id) {
        int updated = jdbc.update("""
                update ejecucion_backup set metadata=(metadata - 'cloudPreparationError') ||
                  jsonb_build_object('cloudRepairAt','1970-01-01T00:00:00Z')
                where id=? and result='EXITO' and metadata->>'cloudRequired'='true'
                  and coalesce(metadata->>'restoreSourceUnavailable','false') <> 'true'
                  and coalesce(metadata->>'cloudAlreadyStored','false') <> 'true'
                """, id);
        if (updated != 1) throw new IllegalStateException("Esta copia no está pendiente de envío a SaaS");
    }

    /** Planned transfer is already fenced: no local-only delivery may be left on the retired PC. */
    public boolean repairAllForTransfer() {
        while (true) {
            var ids = jdbc.query("""
                    select e.id from ejecucion_backup e
                    where e.result='EXITO' and e.metadata->>'cloudRequired'='true'
                      and coalesce(e.metadata->>'restoreSourceUnavailable','false') <> 'true'
                      and coalesce(e.metadata->>'cloudAlreadyStored','false') <> 'true'
                      and not exists(select 1 from cloud_backup_delivery q where q.backup_id=e.id)
                    order by e.iniciada_en limit 20
                    """, (rows, index) -> rows.getObject(1, UUID.class));
            if (ids.isEmpty()) return true;
            for (UUID id : ids) {
                try { repairOne(id); }
                catch (Exception failure) { return false; }
            }
        }
    }

    private static Path sourceFile(Map<String, Object> metadata) {
        for (String key : List.of("path", "monthlyPath")) {
            if (!(metadata.get(key) instanceof String name)) continue;
            Path candidate = Path.of(name).toAbsolutePath().normalize();
            if (!candidate.getFileName().toString().endsWith(".tpvb")) continue;
            boolean linked = false;
            for (Path parent = candidate; parent != null; parent = parent.getParent()) {
                if (Files.isSymbolicLink(parent)) { linked = true; break; }
            }
            if (!linked && Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate;
        }
        throw new IllegalStateException("CLOUD_BACKUP_LOCAL_FILE_MISSING");
    }

    static Map<String, Object> descriptorMetadata(CloudBackupDescriptor value) {
        return Map.ofEntries(Map.entry("backupId", value.backupId().toString()),
                Map.entry("installationId", value.installationId().toString()), Map.entry("sequenceNumber", value.sequenceNumber()),
                Map.entry("capturedAt", value.capturedAt().toString()), Map.entry("applicationVersion", value.applicationVersion()),
                Map.entry("formatVersion", value.formatVersion()), Map.entry("portableSecretsVersion", value.portableSecretsVersion()),
                Map.entry("sizeBytes", value.sizeBytes()), Map.entry("sha256", value.sha256()),
                Map.entry("recoveryKeySha256", value.recoveryKeySha256()), Map.entry("recoveryKeyVersion", value.recoveryKeyVersion()));
    }

    static String sha256(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536];
            for (int read; (read = stream.read(buffer)) != -1;) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Source(String metadata, long sequence) { }
}

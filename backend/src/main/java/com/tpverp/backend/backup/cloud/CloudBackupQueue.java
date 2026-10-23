package com.tpverp.backend.backup.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import com.tpverp.backend.servertransfer.ServerTransferFence;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Durable encrypted spool and at-least-once delivery. Sales never call the network path. */
@Service
public class CloudBackupQueue {
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(30);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final LicenseSaasCredentialStore credentials;
    private final ServerTransferFence transferFence;
    private final Path spool;
    private final String base;
    private final HttpClient http;

    public CloudBackupQueue(JdbcTemplate jdbc, ObjectMapper json, LicenseSaasCredentialStore credentials,
            ServerTransferFence transferFence,
            @Value("${tpv.backup.cloud-spool-directory:${tpv.installation.key-directory}/cloud-backup-spool}") Path spool,
            @Value("${tpv.license.saas-url:}") String saasUrl) {
        this.jdbc = jdbc;
        this.json = json;
        this.credentials = credentials;
        this.transferFence = transferFence;
        this.spool = spool.toAbsolutePath().normalize();
        this.base = saasUrl.replaceAll("/+$", "") + "/api/v1/backup-vault";
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Must be called after local verification and before local retention runs. */
    public void enqueue(CloudBackupDescriptor descriptor, Path encryptedBackup, byte[] wrappedRecoveryKey) {
        validate(descriptor, encryptedBackup, wrappedRecoveryKey);
        try {
            Path directory = directory();
            Path data = directory.resolve(descriptor.backupId() + ".tpvb");
            Path key = directory.resolve(descriptor.backupId() + ".recovery-v2");
            if (Files.exists(data, LinkOption.NOFOLLOW_LINKS)) verify(data, descriptor);
            else copyVerified(encryptedBackup, data, descriptor);
            if (Files.exists(key, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(key, LinkOption.NOFOLLOW_LINKS) || Files.size(key) > 1024 * 1024
                        || !MessageDigest.isEqual(Files.readAllBytes(key), wrappedRecoveryKey))
                    throw new IllegalStateException("CLOUD_BACKUP_SPOOL_KEY_CONFLICT");
            } else writeNew(key, wrappedRecoveryKey);
            String body = json.writeValueAsString(descriptor);
            int inserted = jdbc.update("""
                insert into cloud_backup_delivery(backup_id,installation_id,descriptor,spool_sha256,state)
                values (?,?,cast(? as jsonb),?,'PENDING') on conflict (backup_id) do nothing
                """, descriptor.backupId(), descriptor.installationId(), body, descriptor.sha256());
            if (inserted == 0) {
                String existing = jdbc.queryForObject("select descriptor::text from cloud_backup_delivery where backup_id=?",
                        String.class, descriptor.backupId());
                if (!json.readTree(existing).equals(json.readTree(body)))
                    throw new IllegalStateException("CLOUD_BACKUP_ID_CONFLICT");
                // Re-enqueuing an acknowledged backup must not leave recreated files
                // behind an already populated spool_purged_at marker.
                if (acknowledged(state(descriptor.backupId()))) {
                    jdbc.update("update cloud_backup_delivery set spool_purged_at=null where backup_id=? and state in ('SENT','RETIRED')",
                            descriptor.backupId());
                    discardAcknowledgedSpool(descriptor.backupId());
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("CLOUD_BACKUP_SPOOL_FAILED", exception);
        }
    }

    public CloudBackupStatus status(UUID backupId) {
        return jdbc.query("select state,attempts,uploaded_at,next_attempt_at,error_reason from cloud_backup_delivery where backup_id=?",
                (rs, row) -> new CloudBackupStatus(rs.getString(1), rs.getInt(2), timestamp(rs.getTimestamp(3)),
                        timestamp(rs.getTimestamp(4)), rs.getString(5)), backupId).stream().findFirst().orElse(null);
    }

    public Map<UUID, CloudBackupStatus> statuses(Collection<UUID> backupIds) {
        if (backupIds == null || backupIds.isEmpty()) return Map.of();
        if (backupIds.size() > 1000) throw new IllegalArgumentException("Too many backup IDs");
        var result = new java.util.HashMap<UUID, CloudBackupStatus>();
        var values = jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                select backup_id,state,attempts,uploaded_at,next_attempt_at,error_reason
                from cloud_backup_delivery where backup_id = any (?)
                """);
            statement.setArray(1, connection.createArrayOf("uuid", backupIds.toArray(UUID[]::new)));
            return statement;
        }, (rs, index) -> Map.entry(rs.getObject(1, UUID.class), new CloudBackupStatus(rs.getString(2),
                rs.getInt(3), timestamp(rs.getTimestamp(4)), timestamp(rs.getTimestamp(5)), rs.getString(6))));
        for (var value : values) result.put(value.getKey(), value.getValue());
        return Map.copyOf(result);
    }

    /** Explicit retry after an operator resolves connectivity; the existing verified spool is retained. */
    public CloudBackupStatus retry(UUID backupId) {
        jdbc.update("""
            update cloud_backup_delivery set state='PENDING',next_attempt_at=now(),error_reason=null,updated_at=now()
            where backup_id=? and state in ('PENDING','FAILED')
            """, backupId);
        return status(backupId);
    }

    @Scheduled(fixedDelayString = "${tpv.backup.cloud-retry-delay-ms:30000}")
    public void deliverDue() {
        if (transferFence.frozen() || base.startsWith("/api/")) return;
        UUID due = jdbc.query("""
            select backup_id from cloud_backup_delivery
            where (state in ('PENDING','FAILED') and next_attempt_at <= now())
                or (state='UPLOADING' and lease_until < now())
            order by next_attempt_at,created_at limit 1
            """, (rs, row) -> rs.getObject(1, UUID.class)).stream().findFirst().orElse(null);
        if (due != null) runOne(due, false);
    }

    /** Called only by the sealed transfer workflow while normal workers are fenced. */
    public boolean runOneForTransfer(UUID backupId) {
        jdbc.update("update cloud_backup_delivery set next_attempt_at=now() where backup_id=? and state in ('PENDING','FAILED')",
                backupId);
        Instant deadline = Instant.now().plusSeconds(60);
        while (activeLease(backupId) && Instant.now().isBefore(deadline)) {
            try { Thread.sleep(250); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        }
        return runOne(backupId, true);
    }

    /** Drain every durable copy before the old installation is sealed and its spool becomes inaccessible. */
    public boolean runAllForTransfer() {
        var outstanding = jdbc.query("""
            select backup_id from cloud_backup_delivery
            where state not in ('SENT','RETIRED')
            order by created_at,backup_id
            """, (rs, index) -> rs.getObject(1, UUID.class));
        boolean delivered = true;
        for (UUID backupId : outstanding) {
            if (!runOneForTransfer(backupId)) delivered = false;
        }
        Integer remaining = jdbc.queryForObject("""
            select count(*) from cloud_backup_delivery where state not in ('SENT','RETIRED')
            """, Integer.class);
        return delivered && remaining != null && remaining == 0;
    }

    private boolean runOne(UUID backupId, boolean transfer) {
        if (!transfer && transferFence.frozen()) return false;
        UUID lease = UUID.randomUUID();
        int claimed = jdbc.update("""
            update cloud_backup_delivery set state='UPLOADING',lease_token=?,lease_until=now()+interval '45 minutes',
                attempts=attempts+1,updated_at=now()
            where backup_id=? and ((state in ('PENDING','FAILED') and next_attempt_at <= now())
                or (state='UPLOADING' and lease_until < now()))
            """, lease, backupId);
        if (claimed == 0) return acknowledged(state(backupId));
        try {
            DescriptorRow row = descriptor(backupId);
            Path data = directory().resolve(backupId + ".tpvb");
            Path key = directory().resolve(backupId + ".recovery-v2");
            verify(data, row.descriptor());
            if (!Files.isRegularFile(key, LinkOption.NOFOLLOW_LINKS) || Files.size(key) > 1024 * 1024)
                throw new IOException("CLOUD_BACKUP_KEY_MISSING");
            String token = credentials.readToken().orElseThrow(() -> new IOException("CLOUD_BACKUP_TOKEN_MISSING"));
            String endpoint = base + "/" + row.descriptor().installationId() + "/" + backupId;
            var begin = Map.of("descriptor", row.descriptor(),
                    "wrappedRecoveryKey", Base64.getEncoder().encodeToString(Files.readAllBytes(key)));
            requireResponse(HttpRequest.newBuilder(URI.create(endpoint + "/begin"))
                    .header("X-TPV-Installation-Token", token).header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(begin))).build());
            String status = remoteState(endpoint, token);
            if ("PENDING".equals(status)) {
                requireResponse(HttpRequest.newBuilder(URI.create(endpoint + "/content"))
                        .header("X-TPV-Installation-Token", token).header("Content-Type", "application/octet-stream")
                        .timeout(REQUEST_TIMEOUT).PUT(HttpRequest.BodyPublishers.ofFile(data)).build());
                status = remoteState(endpoint, token);
            }
            if (!"AVAILABLE".equals(status) && !"EVICTED".equals(status))
                throw new IOException("CLOUD_BACKUP_ACK_MISSING");
            refreshRetention(row.descriptor().installationId(), token);
            int marked = jdbc.update("""
                update cloud_backup_delivery set state=?,uploaded_at=now(),lease_token=null,lease_until=null,
                    error_reason=null,updated_at=now() where backup_id=? and lease_token=?
                """, "AVAILABLE".equals(status) ? "SENT" : "RETIRED", backupId, lease);
            if (marked == 1) discardAcknowledgedSpool(backupId);
            return marked == 1 || acknowledged(state(backupId));
        } catch (Exception failure) {
            int attempts = attempts(backupId);
            long delay = Math.min(3600, 10L << Math.min(8, Math.max(0, attempts - 1)));
            jdbc.update("""
                update cloud_backup_delivery set state='FAILED',lease_token=null,lease_until=null,
                    next_attempt_at=now()+(? * interval '1 second'),error_reason=?,updated_at=now()
                where backup_id=? and lease_token=?
                """, delay, reason(failure), backupId, lease);
            return false;
        }
    }

    private void refreshRetention(UUID installationId, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + "/" + installationId + "/available"))
                .header("X-TPV-Installation-Token", token).timeout(Duration.ofSeconds(30)).GET().build();
        var response = requireResponse(request);
        UUID[] active = json.readValue(response, UUID[].class);
        if (active.length > 3) throw new IOException("CLOUD_BACKUP_RETENTION_INVALID");
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                update cloud_backup_delivery set state='RETIRED',updated_at=now()
                where installation_id=? and state='SENT' and not (backup_id = any (?))
                """);
            statement.setObject(1, installationId);
            statement.setArray(2, connection.createArrayOf("uuid", active));
            return statement;
        });
    }

    @Scheduled(fixedDelayString = "${tpv.backup.cloud-spool-purge-delay-ms:3600000}")
    public void purgeAcknowledgedSpool() {
        if (transferFence.frozen()) return;
        if (!Files.isDirectory(spool, LinkOption.NOFOLLOW_LINKS)) return;
        var acknowledged = jdbc.query("""
            select backup_id from cloud_backup_delivery
            where state in ('SENT','RETIRED') and spool_purged_at is null
            order by uploaded_at limit 1000
            """, (rs, index) -> rs.getObject(1, UUID.class));
        for (UUID id : acknowledged) discardAcknowledgedSpool(id);
    }

    private void discardAcknowledgedSpool(UUID backupId) {
        try {
            Path directory = directory();
            Files.deleteIfExists(directory.resolve(backupId + ".tpvb"));
            Files.deleteIfExists(directory.resolve(backupId + ".recovery-v2"));
            jdbc.update("update cloud_backup_delivery set spool_purged_at=now() where backup_id=? and state in ('SENT','RETIRED')",
                    backupId);
        } catch (IOException ignored) { /* A later pass retries cleanup. */ }
    }

    private String remoteState(String endpoint, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(endpoint + "/status"))
                .header("X-TPV-Installation-Token", token).timeout(REQUEST_TIMEOUT).GET().build();
        return json.readTree(requireResponse(request)).path("state").asText();
    }
    private String requireResponse(HttpRequest request) throws IOException, InterruptedException {
        var response = http.send(request, HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofString(), 65536));
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("CLOUD_BACKUP_HTTP_" + response.statusCode());
        return response.body();
    }
    private DescriptorRow descriptor(UUID backupId) throws Exception {
        String value = jdbc.queryForObject("select descriptor::text from cloud_backup_delivery where backup_id=?", String.class, backupId);
        return new DescriptorRow(json.readValue(value, CloudBackupDescriptor.class));
    }
    private int attempts(UUID id) {
        Integer value = jdbc.queryForObject("select attempts from cloud_backup_delivery where backup_id=?", Integer.class, id);
        return value == null ? 1 : value;
    }
    private String state(UUID id) {
        return jdbc.query("select state from cloud_backup_delivery where backup_id=?", (rs, index) -> rs.getString(1), id)
                .stream().findFirst().orElse(null);
    }
    private static boolean acknowledged(String state) { return "SENT".equals(state) || "RETIRED".equals(state); }
    private boolean activeLease(UUID id) {
        Boolean value = jdbc.query("""
            select state='UPLOADING' and lease_until > now()
            from cloud_backup_delivery where backup_id=?
            """, (rs, index) -> rs.getBoolean(1), id).stream().findFirst().orElse(false);
        return Boolean.TRUE.equals(value);
    }
    private Path directory() throws IOException {
        for (Path current = spool; current != null; current = current.getParent()) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                var attributes = Files.readAttributes(current, java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther())
                    throw new IOException("CLOUD_BACKUP_SPOOL_SYMLINK");
            }
        }
        Files.createDirectories(spool);
        if (Files.getFileStore(spool).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(spool,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        if (!Files.isDirectory(spool, LinkOption.NOFOLLOW_LINKS)) throw new IOException("CLOUD_BACKUP_SPOOL_INVALID");
        return spool;
    }
    private static void writeNew(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".cloud-key-", ".part");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void copyVerified(Path source, Path target, CloudBackupDescriptor descriptor) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".cloud-data-", ".part");
        try {
            MessageDigest digest = digest();
            long count = 0;
            try (InputStream input = new DigestInputStream(Files.newInputStream(source), digest);
                    var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024]; int read;
                while ((read = input.read(buffer)) != -1) {
                    count = Math.addExact(count, read);
                    if (count > descriptor.sizeBytes()) throw new IOException("CLOUD_BACKUP_SIZE_MISMATCH");
                    output.write(buffer, 0, read);
                }
            }
            if (count != descriptor.sizeBytes() || !HexFormat.of().formatHex(digest.digest()).equals(descriptor.sha256()))
                throw new IOException("CLOUD_BACKUP_HASH_MISMATCH");
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void verify(Path file, CloudBackupDescriptor descriptor) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != descriptor.sizeBytes())
            throw new IOException("CLOUD_BACKUP_SPOOL_MISSING");
        MessageDigest digest = digest();
        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(descriptor.sha256()))
            throw new IOException("CLOUD_BACKUP_SPOOL_CORRUPT");
    }
    private static void validate(CloudBackupDescriptor d, Path source, byte[] key) {
        if (d == null || d.backupId() == null || d.installationId() == null || d.sequenceNumber() <= 0
                || d.sizeBytes() <= 0 || d.sha256() == null || !d.sha256().matches("[0-9a-f]{64}")
                || d.recoveryKeySha256() == null || !d.recoveryKeySha256().matches("[0-9a-f]{64}")
                || d.recoveryKeyVersion() != 2 || key == null || key.length == 0 || key.length > 1024 * 1024
                || source == null || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("CLOUD_BACKUP_DESCRIPTOR_INVALID");
        if (!HexFormat.of().formatHex(digest().digest(key)).equals(d.recoveryKeySha256()))
            throw new IllegalArgumentException("CLOUD_BACKUP_KEY_HASH_MISMATCH");
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Instant timestamp(java.sql.Timestamp value) { return value == null ? null : value.toInstant(); }
    private static String reason(Exception exception) {
        String message = exception.getMessage();
        if (message == null || !message.matches("[A-Z0-9_]{4,120}")) return "CLOUD_BACKUP_DELIVERY_FAILED";
        return message;
    }
    private record DescriptorRow(CloudBackupDescriptor descriptor) { }
}

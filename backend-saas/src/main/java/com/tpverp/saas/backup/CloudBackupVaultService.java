package com.tpverp.saas.backup;

import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasInstallationRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

/** Opaque encrypted object vault. No commercial data or recovery secret is interpreted here. */
@Service
public class CloudBackupVaultService {
    private final JdbcTemplate jdbc;
    private final SaasInstallationRepository installations;
    private final InstallationAuthenticator authenticator;
    private final Path root;
    private final long maxBytes;
    private final TransactionTemplate transactions;

    public CloudBackupVaultService(JdbcTemplate jdbc, SaasInstallationRepository installations,
            InstallationAuthenticator authenticator,
            @Value("${tpv.saas.backup-vault.directory:/var/lib/tpv-saas/backup-vault}") Path root,
            @Value("${tpv.saas.backup-vault.max-object-bytes:21474836480}") long maxBytes,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.installations = installations;
        this.authenticator = authenticator;
        this.root = root.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public Status begin(CloudBackupDescriptor descriptor, byte[] wrappedRecoveryKey, String token) {
        validate(descriptor, wrappedRecoveryKey);
        SaasInstallation installation = lockAndAuthenticate(descriptor.installationId(), token);
        Row prior = row(descriptor.installationId(), descriptor.backupId());
        if (prior != null) {
            if (!sameDescriptor(prior.descriptor(), descriptor)
                    || !MessageDigest.isEqual(prior.wrappedRecoveryKey(), wrappedRecoveryKey)) {
                throw failure(HttpStatus.CONFLICT, "BACKUP_ID_CONFLICT");
            }
            if ("EVICTED".equals(prior.state())) return new Status(descriptor.backupId(), "EVICTED");
            return new Status(descriptor.backupId(), prior.state());
        }
        if (jdbc.queryForObject("select count(*) from saas_cloud_backup where backup_id=?", Long.class,
                descriptor.backupId()) > 0) throw failure(HttpStatus.CONFLICT, "BACKUP_ID_CONFLICT");
        jdbc.update("""
            insert into saas_cloud_backup(backup_id, installation_id, company_id, store_id,
                sequence_number, captured_at, application_version, format_version,
                portable_secrets_version, size_bytes, sha256, recovery_key_sha256,
                recovery_key_version, wrapped_recovery_key, state)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING')
            """, descriptor.backupId(), descriptor.installationId(), installation.getCompany().getId(),
                installation.getStore().getId(), descriptor.sequenceNumber(), java.sql.Timestamp.from(descriptor.capturedAt()),
                descriptor.applicationVersion(), descriptor.formatVersion(), descriptor.portableSecretsVersion(),
                descriptor.sizeBytes(), descriptor.sha256(), descriptor.recoveryKeySha256(),
                descriptor.recoveryKeyVersion(), wrappedRecoveryKey);
        return new Status(descriptor.backupId(), "PENDING");
    }

    public Status upload(UUID installationId, UUID backupId, String token, InputStream source) throws IOException {
        // Authentication is repeated after streaming, under the installation lock, before acknowledgement.
        authenticated(installationId, token);
        Row initial = requireRow(installationId, backupId);
        if ("AVAILABLE".equals(initial.state())) {
            verifyFile(initial.descriptor());
            return transactions.execute(transaction -> {
                lockAndAuthenticate(installationId, token);
                Row current = requireRow(installationId, backupId);
                if (!"AVAILABLE".equals(current.state()) || !sameDescriptor(initial.descriptor(), current.descriptor()))
                    throw failure(HttpStatus.CONFLICT, "BACKUP_STATE_CONFLICT");
                return new Status(backupId, "AVAILABLE");
            });
        }
        if ("EVICTED".equals(initial.state())) return transactions.execute(transaction -> {
            lockAndAuthenticate(installationId, token);
            return new Status(backupId, requireRow(installationId, backupId).state());
        });
        if (!"PENDING".equals(initial.state())) throw failure(HttpStatus.CONFLICT, "BACKUP_STATE_CONFLICT");
        Path directory = installationDirectory(installationId);
        Path temporary = Files.createTempFile(directory, ".upload-", ".part");
        try {
            MessageDigest digest = digest();
            long written = 0;
            try (InputStream in = new DigestInputStream(source, digest);
                    var out = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    written = Math.addExact(written, read);
                    if (written > initial.descriptor().sizeBytes() || written > maxBytes)
                        throw failure(HttpStatus.BAD_REQUEST, "BACKUP_SIZE_MISMATCH");
                    out.write(buffer, 0, read);
                }
            }
            if (written != initial.descriptor().sizeBytes()
                    || !HexFormat.of().formatHex(digest.digest()).equals(initial.descriptor().sha256()))
                throw failure(HttpStatus.BAD_REQUEST, "BACKUP_HASH_MISMATCH");
            try {
                return transactions.execute(transaction -> {
                    try { return promote(installationId, backupId, token, temporary); }
                    catch (IOException exception) { throw new UncheckedIOException(exception); }
                });
            } catch (UncheckedIOException exception) {
                throw exception.getCause();
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Transactional
    public Status status(UUID installationId, UUID backupId, String token) throws IOException {
        lockAndAuthenticate(installationId, token);
        Row value = requireRow(installationId, backupId);
        // A lost upload response is retried through this acknowledgement. Never authorize
        // deletion of the local spool solely because the database still says AVAILABLE.
        if ("AVAILABLE".equals(value.state())) verifyFile(value.descriptor());
        return new Status(backupId, value.state());
    }

    public List<UUID> available(UUID installationId, String token) {
        authenticated(installationId, token);
        return jdbc.query("select backup_id from saas_cloud_backup where installation_id=? and state='AVAILABLE' order by captured_at desc, sequence_number desc, backup_id desc",
                (rs, index) -> rs.getObject(1, UUID.class), installationId);
    }

    /** Internal support view; caller must enforce ticket and company authorization. */
    public List<CloudBackupDescriptor> listAvailable(UUID installationId) {
        return jdbc.query("""
            select * from saas_cloud_backup where installation_id=? and state='AVAILABLE'
            order by captured_at desc, sequence_number desc, backup_id desc
            """, (rs, index) -> new CloudBackupDescriptor(rs.getObject("backup_id", UUID.class),
                    rs.getObject("installation_id", UUID.class), rs.getLong("sequence_number"),
                    rs.getTimestamp("captured_at").toInstant(), rs.getString("application_version"),
                    rs.getInt("format_version"), rs.getInt("portable_secrets_version"),
                    rs.getLong("size_bytes"), rs.getString("sha256"), rs.getString("recovery_key_sha256"),
                    rs.getInt("recovery_key_version")), installationId);
    }

    /** Internal privileged access only; no installation-token download endpoint exists. */
    public CloudBackupDescriptor descriptor(UUID installationId, UUID backupId) {
        Row value = requireAvailable(installationId, backupId);
        return value.descriptor();
    }

    public InputStream openVerified(UUID installationId, UUID backupId) throws IOException {
        CloudBackupDescriptor value = descriptor(installationId, backupId);
        verifyFile(value);
        return Files.newInputStream(objectPath(value));
    }

    public byte[] wrappedRecoveryKey(UUID installationId, UUID backupId) {
        return requireAvailable(installationId, backupId).wrappedRecoveryKey().clone();
    }

    @Transactional
    public void pin(UUID installationId, UUID backupId, UUID recoveryId) {
        Objects.requireNonNull(recoveryId, "recoveryId");
        lock(installationId);
        Row value = requireAvailable(installationId, backupId);
        if (value.pin() != null && !value.pin().equals(recoveryId))
            throw failure(HttpStatus.CONFLICT, "BACKUP_ALREADY_PINNED");
        jdbc.update("update saas_cloud_backup set pinned_recovery_id=? where backup_id=? and installation_id=?",
                recoveryId, backupId, installationId);
    }

    @Transactional
    public void unpin(UUID installationId, UUID backupId, UUID recoveryId) {
        Objects.requireNonNull(recoveryId, "recoveryId");
        lock(installationId);
        jdbc.update("update saas_cloud_backup set pinned_recovery_id=null where backup_id=? and installation_id=? and pinned_recovery_id=?",
                backupId, installationId, recoveryId);
    }

    public long maxSequenceNumber(UUID installationId) {
        // A begun upload has already issued its visible ID even if the old server fails before sending its bytes.
        Long number = jdbc.queryForObject("select coalesce(max(sequence_number),0) from saas_cloud_backup where installation_id=?",
                Long.class, installationId);
        return number == null ? 0 : number;
    }

    /** Crash-safe cleanup: only database-confirmed evictions are eligible for deletion. */
    @Scheduled(fixedDelayString = "${tpv.saas.backup-vault.purge-delay-ms:3600000}",
            initialDelayString = "${tpv.saas.backup-vault.purge-initial-delay-ms:60000}")
    public void purgeEvicted() {
        List<CloudBackupDescriptor> evicted = jdbc.query("""
            select * from saas_cloud_backup where state='EVICTED' and evicted_at is not null and purged_at is null
            order by evicted_at limit 1000
            """, (rs, index) -> new CloudBackupDescriptor(rs.getObject("backup_id", UUID.class),
                    rs.getObject("installation_id", UUID.class), rs.getLong("sequence_number"),
                    rs.getTimestamp("captured_at").toInstant(), rs.getString("application_version"),
                    rs.getInt("format_version"), rs.getInt("portable_secrets_version"),
                    rs.getLong("size_bytes"), rs.getString("sha256"), rs.getString("recovery_key_sha256"),
                    rs.getInt("recovery_key_version")));
        for (CloudBackupDescriptor value : evicted) {
            try {
                Files.deleteIfExists(objectPath(value));
                jdbc.update("update saas_cloud_backup set purged_at=now() where backup_id=? and state='EVICTED'",
                        value.backupId());
            }
            catch (IOException ignored) { /* Next scheduled pass will retry. */ }
        }
    }

    private Status promote(UUID installationId, UUID backupId, String token, Path temporary) throws IOException {
        lockAndAuthenticate(installationId, token);
        Row value = requireRow(installationId, backupId);
        if ("AVAILABLE".equals(value.state())) {
            verifyFile(value.descriptor());
            return new Status(backupId, "AVAILABLE");
        }
        if ("EVICTED".equals(value.state())) return new Status(backupId, "EVICTED");
        if (!"PENDING".equals(value.state())) throw failure(HttpStatus.CONFLICT, "BACKUP_STATE_CONFLICT");
        List<UUID> available = jdbc.query("""
            select backup_id from saas_cloud_backup where installation_id=? and state='AVAILABLE'
            order by captured_at asc, sequence_number asc, backup_id asc
            """, (rs, index) -> rs.getObject(1, UUID.class), installationId);
        if (available.size() >= 3) {
            UUID oldestUnpinned = jdbc.query("""
                select backup_id from saas_cloud_backup where installation_id=? and state='AVAILABLE'
                    and pinned_recovery_id is null
                order by captured_at asc, sequence_number asc, backup_id asc limit 1
                """, (rs, index) -> rs.getObject(1, UUID.class), installationId).stream().findFirst().orElse(null);
            if (oldestUnpinned == null) throw failure(HttpStatus.CONFLICT, "BACKUP_RETENTION_PINNED");
            Row oldest = requireRow(installationId, oldestUnpinned);
            if (compare(value.descriptor(), oldest.descriptor()) <= 0) {
                jdbc.update("update saas_cloud_backup set state='EVICTED',evicted_at=now() where backup_id=?", backupId);
                return new Status(backupId, "EVICTED");
            }
            // The new file is fully validated before any older verified copy is evicted.
            Path target = objectPath(value.descriptor());
            placeVerified(temporary, target, value.descriptor());
            jdbc.update("update saas_cloud_backup set state='EVICTED', evicted_at=now() where backup_id=?", oldestUnpinned);
            jdbc.update("update saas_cloud_backup set state='AVAILABLE', available_at=now() where backup_id=?", backupId);
            Path evicted = installationDirectory(installationId).resolve(oldestUnpinned + ".tpvb");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    try { Files.deleteIfExists(evicted); } catch (IOException ignored) { /* Retried by maintenance. */ }
                }
            });
            return new Status(backupId, "AVAILABLE");
        }
        placeVerified(temporary, objectPath(value.descriptor()), value.descriptor());
        jdbc.update("update saas_cloud_backup set state='AVAILABLE', available_at=now() where backup_id=?", backupId);
        return new Status(backupId, "AVAILABLE");
    }

    private void placeVerified(Path temporary, Path target, CloudBackupDescriptor descriptor) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            verifyFile(descriptor);
            return;
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private SaasInstallation authenticated(UUID installationId, String token) {
        SaasInstallation value = installations.findByInstallationId(installationId)
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "INSTALLATION_NOT_FOUND"));
        authenticator.requireToken(value, token);
        return value;
    }
    private SaasInstallation lockAndAuthenticate(UUID installationId, String token) {
        SaasInstallation value = lock(installationId);
        authenticator.requireToken(value, token);
        return value;
    }
    private SaasInstallation lock(UUID installationId) {
        return installations.findByInstallationIdForUpdate(installationId)
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "INSTALLATION_NOT_FOUND"));
    }
    private Row requireAvailable(UUID installationId, UUID backupId) {
        Row value = requireRow(installationId, backupId);
        if (!"AVAILABLE".equals(value.state())) throw failure(HttpStatus.NOT_FOUND, "BACKUP_NOT_AVAILABLE");
        return value;
    }
    private Row requireRow(UUID installationId, UUID backupId) {
        Row value = row(installationId, backupId);
        if (value == null) throw failure(HttpStatus.NOT_FOUND, "BACKUP_NOT_FOUND");
        return value;
    }
    private Row row(UUID installationId, UUID backupId) {
        return jdbc.query("select * from saas_cloud_backup where installation_id=? and backup_id=?", (rs, index) ->
                new Row(new CloudBackupDescriptor(rs.getObject("backup_id", UUID.class), rs.getObject("installation_id", UUID.class),
                        rs.getLong("sequence_number"), rs.getTimestamp("captured_at").toInstant(),
                        rs.getString("application_version"), rs.getInt("format_version"),
                        rs.getInt("portable_secrets_version"), rs.getLong("size_bytes"), rs.getString("sha256"),
                        rs.getString("recovery_key_sha256"), rs.getInt("recovery_key_version")),
                        rs.getBytes("wrapped_recovery_key"), rs.getString("state"), rs.getObject("pinned_recovery_id", UUID.class)),
                installationId, backupId).stream().findFirst().orElse(null);
    }
    private Path installationDirectory(UUID installationId) throws IOException {
        rejectLinkedAncestors(root);
        Files.createDirectories(root);
        ownerOnly(root);
        Path child = root.resolve(installationId.toString());
        rejectLinkedAncestors(child);
        Files.createDirectories(child);
        ownerOnly(child);
        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) throw new IOException("BACKUP_VAULT_DIRECTORY_INVALID");
        return child;
    }
    private Path objectPath(CloudBackupDescriptor descriptor) throws IOException {
        Path path = installationDirectory(descriptor.installationId()).resolve(descriptor.backupId() + ".tpvb");
        rejectLinkedAncestors(path);
        return path;
    }
    private static void rejectLinkedAncestors(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                var attributes = Files.readAttributes(current, java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther())
                    throw new IOException("BACKUP_VAULT_SYMLINK");
            }
        }
    }
    private static void ownerOnly(Path directory) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(directory,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    }
    private void verifyFile(CloudBackupDescriptor descriptor) throws IOException {
        Path file = objectPath(descriptor);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != descriptor.sizeBytes())
            throw new IOException("BACKUP_VAULT_OBJECT_MISSING");
        MessageDigest digest = digest();
        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(descriptor.sha256()))
            throw new IOException("BACKUP_VAULT_OBJECT_CORRUPT");
    }
    private void validate(CloudBackupDescriptor value, byte[] wrapped) {
        if (value == null || value.backupId() == null || value.installationId() == null
                || value.sequenceNumber() <= 0 || value.capturedAt() == null
                || value.capturedAt().isAfter(Instant.now().plusSeconds(300))
                || value.applicationVersion() == null || value.applicationVersion().isBlank()
                || value.applicationVersion().length() > 128 || value.formatVersion() <= 0
                || value.portableSecretsVersion() < 0 || value.recoveryKeyVersion() != 2
                || value.sizeBytes() <= 0 || value.sizeBytes() > maxBytes
                || value.sha256() == null || !value.sha256().matches("[0-9a-f]{64}")
                || value.recoveryKeySha256() == null || !value.recoveryKeySha256().matches("[0-9a-f]{64}")
                || wrapped == null || wrapped.length == 0 || wrapped.length > 1024 * 1024)
            throw failure(HttpStatus.BAD_REQUEST, "BACKUP_DESCRIPTOR_INVALID");
        if (!HexFormat.of().formatHex(digest().digest(wrapped)).equals(value.recoveryKeySha256()))
            throw failure(HttpStatus.BAD_REQUEST, "BACKUP_RECOVERY_KEY_HASH_MISMATCH");
        if (!isWrappedRecoveryV2(wrapped))
            throw failure(HttpStatus.BAD_REQUEST, "BACKUP_RECOVERY_KEY_FORMAT_INVALID");
    }
    private static boolean isWrappedRecoveryV2(byte[] packageBytes) {
        if (packageBytes.length != 91) return false;
        var header = java.nio.ByteBuffer.wrap(packageBytes);
        if (header.get() != 'T' || header.get() != 'P' || header.get() != 'V' || header.get() != 'R'
                || header.get() != 2) return false;
        int iterations = header.getInt();
        return iterations >= 100_000 && iterations <= 10_000_000
                && header.get() == 16 && header.get() == 12 && header.getInt() == 48;
    }
    private static boolean sameDescriptor(CloudBackupDescriptor stored, CloudBackupDescriptor received) {
        return stored.backupId().equals(received.backupId())
                && stored.installationId().equals(received.installationId())
                && stored.sequenceNumber() == received.sequenceNumber()
                && java.time.Duration.between(stored.capturedAt(), received.capturedAt()).abs()
                        .compareTo(java.time.Duration.ofNanos(1000)) < 0
                && stored.applicationVersion().equals(received.applicationVersion())
                && stored.formatVersion() == received.formatVersion()
                && stored.portableSecretsVersion() == received.portableSecretsVersion()
                && stored.sizeBytes() == received.sizeBytes()
                && stored.sha256().equals(received.sha256())
                && stored.recoveryKeySha256().equals(received.recoveryKeySha256())
                && stored.recoveryKeyVersion() == received.recoveryKeyVersion();
    }
    private static int compare(CloudBackupDescriptor left, CloudBackupDescriptor right) {
        int date = left.capturedAt().compareTo(right.capturedAt());
        if (date != 0) return date;
        int sequence = Long.compare(left.sequenceNumber(), right.sequenceNumber());
        return sequence != 0 ? sequence : left.backupId().compareTo(right.backupId());
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ResponseStatusException failure(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }
    private record Row(CloudBackupDescriptor descriptor, byte[] wrappedRecoveryKey, String state, UUID pin) { }
    public record Status(UUID backupId, String state) { }
}

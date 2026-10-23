package com.tpverp.backend.backup;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.backup.application.*;
import com.tpverp.backend.backup.cloud.*;
import com.tpverp.backend.disasterrecovery.RecoverySecretsCodec;
import com.tpverp.backend.installation.*;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.servertransfer.ServerTransferSecrets;
import com.tpverp.backend.verifactu.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/** Exercises the real encrypted artifact at the local-success / durable-delivery boundary. */
class BackupCloudArtifactTest {
    @TempDir Path directory;
    private final Instant capturedAt = Instant.parse("2026-10-23T10:00:00Z");
    private final byte[] brk = new byte[32];
    private final Installation installation = new Installation("test", "public", capturedAt);
    private final BackupExecutionRepository executions = mock(BackupExecutionRepository.class);
    private final CloudBackupQueue queue = mock(CloudBackupQueue.class);
    private final AtomicReference<CloudBackupDescriptor> delivered = new AtomicReference<>();
    private byte[] recoveryPackage;
    private BackupService service;

    @BeforeEach void configure() throws Exception {
        new java.security.SecureRandom().nextBytes(brk);
        var keys = mock(BackupKeyStore.class);
        recoveryPackage = new RecoveryKeyPackage().createV2ForKey(brk,
                "independent recovery phrase".toCharArray(), 100_000);
        when(keys.loadForScheduledBackup()).thenAnswer(call -> brk.clone());
        when(keys.exportWrappedRecoveryKey()).thenAnswer(call -> recoveryPackage.clone());
        var installations = mock(InstallationRepository.class);
        when(installations.findAll()).thenReturn(List.of(installation));
        var settings = mock(BackupSettingsRepository.class);
        var configuration = new BackupSettings(installation, LocalTime.NOON, 30, 72,
                Map.of("path", directory.toString()));
        when(settings.findByInstalacionId(installation.getId())).thenReturn(Optional.of(configuration));
        when(executions.save(any())).thenAnswer(call -> call.getArgument(0));
        service = new BackupService(settings, executions, installations, mock(StoreRepository.class),
                mock(UserAccountRepository.class), mock(PasswordEncoder.class), keys,
                new BackupFileCrypto(1024 * 1024), new BackupArchiveService(), mock(PostgreSqlBackupCommands.class),
                mock(AuditService.class), mock(VerifactuConfigurationRepository.class), mock(FiscalEventService.class),
                Clock.fixed(capturedAt, ZoneOffset.UTC), directory, directory.resolve("images"), directory.resolve("templates"));
        Path privateWorkspace = directory.resolve("protected-keys/backup-work");
        service.configureWorkspace(privateWorkspace);
        var snapshots = mock(BackupSnapshotService.class);
        when(snapshots.capture(any(), any(), any(), any(), any())).thenAnswer(call -> {
            assertThat(call.<Path>getArgument(1).getParent()).isEqualTo(privateWorkspace);
            try (var exported = Files.list(directory)) {
                assertThat(exported.map(path -> path.getFileName().toString()))
                        .noneMatch(name -> name.startsWith(".tpv-dump-") || name.startsWith(".tpv-archive-"));
            }
            Files.writeString(call.getArgument(1), "snapshot database contents");
            var manifest = new LinkedHashMap<String, Object>(Map.of("capturedAt", capturedAt.toString(),
                    "installationId", installation.getId().toString(), "applicationVersion", "test-v277"));
            call.<BackupSnapshotService.SnapshotEnricher>getArgument(4).enrich(mock(Connection.class), manifest);
            var capture = mock(BackupSnapshotService.Capture.class);
            when(capture.manifest()).thenReturn(manifest);
            return capture;
        });
        var secrets = mock(ServerTransferSecrets.class);
        when(secrets.capturePortable(any())).thenAnswer(call -> new ServerTransferSecrets.PortableCapture(
                new HashMap<>(Map.of("installation.private", "portable private identity".getBytes(StandardCharsets.UTF_8))), List.of()));
        service.configureSnapshots(snapshots, "test-v277");
        service.configureCloud(queue, secrets, mock(CloudBackupRepairService.class));
        doAnswer(call -> {
            delivered.set(call.getArgument(0));
            assertThat(call.<byte[]>getArgument(2)).isEqualTo(recoveryPackage);
            return null;
        }).when(queue).enqueue(any(), any(), any());
    }

    @Test void verifiedCopyIncludesBoundPortableSecretsAndMatchingCloudDescriptor() throws Exception {
        var prepared = service.prepare("MANUAL");
        ReflectionTestUtils.setField(prepared.execution(), "sequenceNumber", 42L);
        var result = service.executePrepared(prepared);
        assertThat(result.result()).isEqualTo(BackupResult.EXITO);
        assertThat(result.cloud().state()).isEqualTo("PENDING");
        assertThat(result.metadata()).doesNotContainKeys("cloudRecoveryKey", "cloudDescriptor");
        Path encrypted = directory.resolve("daily/000042.tpvb");
        var descriptor = delivered.get();
        assertThat(descriptor.backupId()).isEqualTo(result.id());
        assertThat(descriptor.installationId()).isEqualTo(installation.getId());
        assertThat(descriptor.sequenceNumber()).isEqualTo(42);
        assertThat(descriptor.capturedAt()).isEqualTo(capturedAt);
        assertThat(descriptor.formatVersion()).isEqualTo(1);
        assertThat(descriptor.recoveryKeyVersion()).isEqualTo(2);
        assertThat(descriptor.sizeBytes()).isEqualTo(Files.size(encrypted));
        assertThat(descriptor.sha256()).isEqualTo(CloudBackupRepairService.sha256(encrypted));
        assertThat(descriptor.recoveryKeySha256()).isEqualTo(CloudBackupRepairService.sha256(recoveryPackage));
        Path zip = directory.resolve("decrypted.zip");
        new BackupFileCrypto(1024 * 1024).decrypt(encrypted, zip, brk);
        try (var archive = new ZipFile(zip.toFile())) {
            assertThat(new String(archive.getInputStream(archive.getEntry("database.backup")).readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("snapshot database contents");
            var manifest = new ObjectMapper().readTree(archive.getInputStream(archive.getEntry("manifest.json")));
            assertThat(manifest.path("portableSecretsComplete").asBoolean()).isTrue();
            assertThat(manifest.path("executionId").asText()).isEqualTo(result.id().toString());
            byte[] capsule = archive.getInputStream(archive.getEntry("recovery-secrets.bin")).readAllBytes();
            var recovered = RecoverySecretsCodec.open(capsule, brk, installation.getId(), result.id());
            assertThat(new String(recovered.get("installation.private"), StandardCharsets.UTF_8)).isEqualTo("portable private identity");
            assertThatThrownBy(() -> RecoverySecretsCodec.open(capsule, brk, installation.getId(), UUID.randomUUID()))
                    .isInstanceOf(java.security.GeneralSecurityException.class);
        }
    }

    @Test void spoolFailureKeepsVerifiedLocalCopyAndDurableRepairMetadata() throws Exception {
        doThrow(new IllegalStateException("spool unavailable")).when(queue).enqueue(any(), any(), any());
        var prepared = service.prepare("AUTOMATIC");
        ReflectionTestUtils.setField(prepared.execution(), "sequenceNumber", 43L);
        var result = service.executePrepared(prepared);
        assertThat(result.result()).isEqualTo(BackupResult.EXITO);
        assertThat(result.cloud().state()).isEqualTo("PENDING");
        Path encrypted = directory.resolve("daily/000043.tpvb");
        assertThat(encrypted).exists();
        new BackupFileCrypto(1024 * 1024).verify(encrypted, brk);
        assertThat(prepared.execution().getMetadata()).containsKeys("cloudRecoveryKey", "cloudDescriptor", "cloudRequired");
        assertThat(result.metadata()).doesNotContainKeys("cloudRecoveryKey", "cloudDescriptor");
    }
}

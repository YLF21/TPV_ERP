package com.tpverp.backend.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.backup.application.BackupArchiveService;
import com.tpverp.backend.backup.application.BackupFileCrypto;
import com.tpverp.backend.backup.application.BackupKeyStore;
import com.tpverp.backend.backup.application.PostgreSqlBackupCommands;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.verifactu.FiscalEventService;
import com.tpverp.backend.verifactu.VerifactuConfigurationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.annotation.Transactional;

class BackupServiceContractTest {

    @TempDir
    private Path tempDir;

    @Test
    void restoreDoesNotHoldJpaTransactionWhilePgRestoreRuns() throws Exception {
        var method = BackupService.class.getMethod(
                "restore", Path.class, Path.class, String.class);

        assertThat(method.getAnnotation(Transactional.class)).isNull();
    }

    @Test
    void bootstrapDoesNotCreateConfigurationOrKnownPasswordKey() {
        var configurations = org.mockito.Mockito.mock(BackupSettingsRepository.class);
        var service = new BackupService(
                configurations,
                org.mockito.Mockito.mock(BackupExecutionRepository.class),
                org.mockito.Mockito.mock(InstallationRepository.class),
                org.mockito.Mockito.mock(StoreRepository.class),
                org.mockito.Mockito.mock(UserAccountRepository.class),
                org.mockito.Mockito.mock(org.springframework.security.crypto.password.PasswordEncoder.class),
                org.mockito.Mockito.mock(BackupKeyStore.class),
                org.mockito.Mockito.mock(BackupFileCrypto.class),
                org.mockito.Mockito.mock(BackupArchiveService.class),
                org.mockito.Mockito.mock(PostgreSqlBackupCommands.class),
                org.mockito.Mockito.mock(AuditService.class),
                org.mockito.Mockito.mock(VerifactuConfigurationRepository.class),
                org.mockito.Mockito.mock(FiscalEventService.class),
                Clock.systemUTC(),
                Path.of("target/test-backups"),
                Path.of("target/test-images"),
                Path.of("target/test-templates"));

        service.initializeDefaultIfMissing();

        org.mockito.Mockito.verifyNoInteractions(configurations);
    }

    @Test
    void completedExecutionIsExplicitlyFlushedWithoutHoldingOneLongTransaction() throws Exception {
        var settingsRepository = org.mockito.Mockito.mock(BackupSettingsRepository.class);
        var executions = org.mockito.Mockito.mock(BackupExecutionRepository.class);
        var installations = org.mockito.Mockito.mock(InstallationRepository.class);
        var keyStore = org.mockito.Mockito.mock(BackupKeyStore.class);
        var crypto = org.mockito.Mockito.mock(BackupFileCrypto.class);
        var archives = org.mockito.Mockito.mock(BackupArchiveService.class);
        var commands = org.mockito.Mockito.mock(PostgreSqlBackupCommands.class);
        var installation = new Installation("INSTALL-TEST", "PUBLIC", Instant.parse("2026-08-01T00:00:00Z"));
        var settings = new BackupSettings(
                installation, LocalTime.NOON, 30, 72, Map.of("path", tempDir.toString()));
        when(installations.findAll()).thenReturn(List.of(installation));
        when(settingsRepository.findByInstalacionId(installation.getId())).thenReturn(Optional.of(settings));
        when(executions.save(any(BackupExecution.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(executions.saveAndFlush(any(BackupExecution.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(keyStore.loadForScheduledBackup()).thenReturn(new byte[32]);
        doAnswer(invocation -> {
            Files.writeString(invocation.getArgument(0), "dump");
            return null;
        }).when(commands).dump(any(Path.class));
        when(archives.create(any(Path.class), any(Path.class), any(Path.class), any(Path.class)))
                .thenAnswer(invocation -> {
                    Files.writeString(invocation.getArgument(3), "archive");
                    return new BackupArchiveService.BackupArchiveInfo(4, 0, 0);
                });
        when(crypto.encrypt(any(Path.class), any(Path.class), any(byte[].class)))
                .thenAnswer(invocation -> {
                    Files.writeString(invocation.getArgument(1), "encrypted");
                    return new BackupFileCrypto.BackupInfo(1, 1024, 7, 1);
                });
        var service = new BackupService(
                settingsRepository, executions, installations,
                org.mockito.Mockito.mock(StoreRepository.class),
                org.mockito.Mockito.mock(UserAccountRepository.class),
                org.mockito.Mockito.mock(org.springframework.security.crypto.password.PasswordEncoder.class),
                keyStore, crypto, archives, commands,
                org.mockito.Mockito.mock(AuditService.class),
                org.mockito.Mockito.mock(VerifactuConfigurationRepository.class),
                org.mockito.Mockito.mock(FiscalEventService.class),
                Clock.fixed(Instant.parse("2026-08-27T10:11:12Z"), ZoneOffset.UTC),
                tempDir, tempDir.resolve("images"), tempDir.resolve("templates"));

        var result = service.executeNow();

        assertThat(result.result()).isEqualTo(BackupResult.EXITO);
        verify(executions).saveAndFlush(any(BackupExecution.class));
        assertThat(Path.of(result.metadata().get("path").toString()).getFileName().toString())
                .startsWith("tpv-erp-2026-08-27-101112-")
                .endsWith(".tpvb");
    }

    @Test
    void onlyASuccessfulExecutionSuppressesTheDailyRetry() {
        var settingsRepository = org.mockito.Mockito.mock(BackupSettingsRepository.class);
        var executions = org.mockito.Mockito.mock(BackupExecutionRepository.class);
        var installations = org.mockito.Mockito.mock(InstallationRepository.class);
        var installation = new Installation("INSTALL-TEST", "PUBLIC", Instant.parse("2026-08-01T00:00:00Z"));
        var settings = new BackupSettings(
                installation, LocalTime.NOON, 30, 72, Map.of("path", tempDir.toString()));
        ZoneId zone = ZoneId.systemDefault();
        LocalDate businessDay = LocalDate.of(2026, 8, 27);
        Instant startOfDay = businessDay.atStartOfDay(zone).toInstant();
        Clock clock = Clock.fixed(businessDay.atTime(13, 0).atZone(zone).toInstant(), zone);
        when(installations.findAll()).thenReturn(List.of(installation));
        when(settingsRepository.findByInstalacionId(installation.getId())).thenReturn(Optional.of(settings));
        when(executions.existsByConfiguracionIdAndResultadoAndIniciadaEnGreaterThanEqual(
                settings.getId(), BackupResult.EXITO, startOfDay))
                .thenReturn(false, true);
        var service = new BackupService(
                settingsRepository,
                executions,
                installations,
                org.mockito.Mockito.mock(StoreRepository.class),
                org.mockito.Mockito.mock(UserAccountRepository.class),
                org.mockito.Mockito.mock(org.springframework.security.crypto.password.PasswordEncoder.class),
                org.mockito.Mockito.mock(BackupKeyStore.class),
                org.mockito.Mockito.mock(BackupFileCrypto.class),
                org.mockito.Mockito.mock(BackupArchiveService.class),
                org.mockito.Mockito.mock(PostgreSqlBackupCommands.class),
                org.mockito.Mockito.mock(AuditService.class),
                org.mockito.Mockito.mock(VerifactuConfigurationRepository.class),
                org.mockito.Mockito.mock(FiscalEventService.class),
                clock,
                tempDir,
                tempDir.resolve("images"),
                tempDir.resolve("templates"));

        assertThat(service.isDue()).isTrue();
        assertThat(service.isDue()).isFalse();

        org.mockito.Mockito.verify(executions, org.mockito.Mockito.times(2))
                .existsByConfiguracionIdAndResultadoAndIniciadaEnGreaterThanEqual(
                        settings.getId(), BackupResult.EXITO, startOfDay);
    }

    @Test
    void housekeepingIsAfterLeaseConfirmationAndCannotDeleteUnconfirmedDaily() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/tpverp/backend/backup/BackupService.java"));
        assertThat(source.indexOf("leaseService.complete(lease, metadata)"))
                .isLessThan(source.indexOf("createMonthlyCopyIfNeeded"));
        assertThat(source).contains("if (!executionCommitted && !preserveEncrypted && encrypted != null)");
        assertThat(source).contains("BACKUP_HOUSEKEEPING_FAILED");
    }
}

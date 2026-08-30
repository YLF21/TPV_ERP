package com.tpverp.backend.backup;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.backup.application.BackupArchiveService;
import com.tpverp.backend.backup.application.BackupFileCrypto;
import com.tpverp.backend.backup.application.BackupKeyStore;
import com.tpverp.backend.backup.application.PostgreSqlBackupCommands;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.shared.crypto.SecretProtectorFactory;
import com.tpverp.backend.verifactu.FiscalEventService;
import com.tpverp.backend.verifactu.VerifactuConfigurationRepository;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

@Configuration
class BackupConfiguration {

    @Bean
    BackupKeyStore backupKeyStore(
            @Value("${tpv.installation.key-directory}") Path keyDirectory,
            @Value("${tpv.installation.portable-secret-key:}") String portableSecretKey) {
        return new BackupKeyStore(
                keyDirectory,
                SecretProtectorFactory.portableOrWindowsDpapi(portableSecretKey));
    }

    @Bean
    BackupFileCrypto backupFileCrypto() {
        return new BackupFileCrypto(1024 * 1024);
    }

    @Bean
    BackupArchiveService backupArchiveService(
            @Value("${tpv.backup.archive.max-entries:100000}") int maxEntries,
            @Value("${tpv.backup.archive.max-entry-bytes:268435456}") long maxEntryBytes,
            @Value("${tpv.backup.archive.max-total-bytes:2147483648}") long maxTotalBytes) {
        return new BackupArchiveService(maxEntries, maxEntryBytes, maxTotalBytes);
    }

    @Bean
    PostgreSqlBackupCommands postgreSqlBackupCommands(
            @Value("${spring.datasource.url}") String databaseUrl,
            @Value("${spring.datasource.username}") String databaseUser,
            @Value("${spring.datasource.password}") String databasePassword,
            @Value("${tpv.backup.pg-dump-command:pg_dump}") String pgDumpCommand,
            @Value("${tpv.backup.pg-restore-command:pg_restore}") String pgRestoreCommand) {
        return new PostgreSqlBackupCommands(
                databaseUrl,
                databaseUser,
                databasePassword,
                pgDumpCommand,
                pgRestoreCommand);
    }

    @Bean
    BackupService backupService(
            BackupSettingsRepository configurationRepository,
            BackupExecutionRepository executionRepository,
            InstallationRepository installationRepository,
            StoreRepository storeRepository,
            UserAccountRepository userRepository,
            PasswordEncoder passwordEncoder,
            BackupKeyStore keyStore,
            BackupFileCrypto fileCrypto,
            BackupArchiveService archives,
            PostgreSqlBackupCommands commands,
            AuditService auditService,
            VerifactuConfigurationRepository verifactuConfigurations,
            FiscalEventService fiscalEvents,
            Clock clock,
            @Value("${tpv.backup.default-directory}") Path defaultDirectory,
            @Value("${tpv.product-images.directory:${tpv.backup.default-directory}/product-images}") Path productImagesDirectory,
            @Value("${tpv.document-templates.directory:${tpv.backup.default-directory}/document-templates}") Path documentTemplatesDirectory,
            @Value("${tpv.backup.restore-online-enabled:false}") boolean restoreOnlineEnabled,
            BackupExecutionLeaseService leaseService) {
        return new BackupService(
                configurationRepository,
                executionRepository,
                installationRepository,
                storeRepository,
                userRepository,
                passwordEncoder,
                keyStore,
                fileCrypto,
                archives,
                commands,
                auditService,
                verifactuConfigurations,
                fiscalEvents,
                clock,
                defaultDirectory,
                productImagesDirectory,
                documentTemplatesDirectory,
                restoreOnlineEnabled,
                leaseService);
    }

    @Bean
    BackupJobLauncher backupJobLauncher(BackupService backupService) {
        return new BackupJobLauncher(backupService);
    }

    @Bean
    @Order(20)
    ApplicationRunner defaultBackupConfigurationRunner(BackupService backupService) {
        return arguments -> backupService.initializeDefaultIfMissing();
    }

    @Bean
    @Order(1)
    ApplicationRunner restoreJournalGuard(
            BackupRestoreFinalizeService finalizer,
            @Value("${tpv.backup.restore-journal-path:}") String configuredJournal,
            Environment environment) {
        return arguments -> {
            var values = arguments.getOptionValues("tpv.restore-finalize");
            if (values != null && !values.isEmpty()) {
                finalizer.finalizeRestore(BackupRestoreJournalReader.normalize(Path.of(values.getLast())));
                return;
            }
            if (!java.util.Arrays.asList(environment.getActiveProfiles()).contains("prod")) return;
            Path journal = BackupRestoreJournalReader.normalize(Path.of(
                    configuredJournal == null || configuredJournal.isBlank()
                            ? BackupRestoreJournalReader.DEFAULT_PRODUCTION_PATH : configuredJournal));
            if (!java.nio.file.Files.exists(journal, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
            java.util.Properties state = BackupRestoreJournalReader.read(journal);
            if ("FINALIZED".equals(BackupRestoreJournalReader.phase(state))) {
                // This also verifies the DB marker; a stale FINALIZED file is not sufficient.
                finalizer.finalizeRestore(journal);
            } else {
                throw new IllegalStateException(
                        "Existe un journal de restauración pendiente; ejecute el finalize offline antes de arrancar en producción");
            }
        };
    }

    @Bean
    BackupSchedule backupSchedule(BackupService backupService, BackupJobLauncher launcher) {
        return new BackupSchedule(backupService, launcher);
    }

    static final class BackupSchedule {
        private final BackupService backupService;
        private final BackupJobLauncher launcher;

        BackupSchedule(BackupService backupService, BackupJobLauncher launcher) {
            this.backupService = backupService;
            this.launcher = launcher;
        }

        @Scheduled(cron = "0 * * * * *")
        void runDueBackup() {
            try {
                if (backupService.isDue()) {
                    launcher.launch();
                }
            } catch (IllegalStateException ignored) {
                // Backups are optional until the administrator configures them.
            }
        }
    }
}

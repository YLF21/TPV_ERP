package com.tpverp.backend.backup;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.backup.application.BackupArchiveService;
import com.tpverp.backend.backup.application.BackupFileCrypto;
import com.tpverp.backend.backup.application.BackupKeyStore;
import com.tpverp.backend.backup.application.PostgreSqlBackupCommands;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.shared.crypto.WindowsDpapiSecretProtector;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;

@Configuration
class BackupConfiguration {

    @Bean
    BackupKeyStore backupKeyStore(
            @Value("${tpv.installation.key-directory}") Path keyDirectory) {
        return new BackupKeyStore(keyDirectory, new WindowsDpapiSecretProtector());
    }

    @Bean
    BackupFileCrypto backupFileCrypto() {
        return new BackupFileCrypto(1024 * 1024);
    }

    @Bean
    BackupArchiveService backupArchiveService() {
        return new BackupArchiveService();
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
            Clock clock,
            @Value("${tpv.backup.default-directory}") Path defaultDirectory,
            @Value("${tpv.product-images.directory:${tpv.backup.default-directory}/product-images}") Path productImagesDirectory) {
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
                clock,
                defaultDirectory,
                productImagesDirectory);
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

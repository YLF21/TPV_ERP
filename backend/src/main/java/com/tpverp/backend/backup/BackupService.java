package com.tpverp.backend.backup;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.backup.application.BackupFileCrypto;
import com.tpverp.backend.backup.application.BackupArchiveService;
import com.tpverp.backend.backup.application.BackupKeyStore;
import com.tpverp.backend.backup.application.PostgreSqlBackupCommands;
import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.verifactu.FiscalEventService;
import com.tpverp.backend.verifactu.FiscalEventType;
import com.tpverp.backend.verifactu.FiscalMode;
import com.tpverp.backend.verifactu.VerifactuConfigurationRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class BackupService {

    private static final DateTimeFormatter BACKUP_FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    private final BackupSettingsRepository configurationRepository;
    private final BackupExecutionRepository executionRepository;
    private final InstallationRepository installationRepository;
    private final StoreRepository storeRepository;
    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final BackupKeyStore keyStore;
    private final BackupFileCrypto fileCrypto;
    private final BackupArchiveService archives;
    private final PostgreSqlBackupCommands commands;
    private final AuditService auditService;
    private final VerifactuConfigurationRepository verifactuConfigurations;
    private final FiscalEventService fiscalEvents;
    private final Clock clock;
    private final Path defaultDirectory;
    private final Path productImagesDirectory;
    private final Path documentTemplatesDirectory;
    private final boolean restoreOnlineEnabled;
    private final BackupExecutionLeaseService leaseService;

    public BackupService(
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
            Path defaultDirectory,
            Path productImagesDirectory,
            Path documentTemplatesDirectory,
            boolean restoreOnlineEnabled,
            BackupExecutionLeaseService leaseService) {
        this.configurationRepository = configurationRepository;
        this.executionRepository = executionRepository;
        this.installationRepository = installationRepository;
        this.storeRepository = storeRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.keyStore = keyStore;
        this.fileCrypto = fileCrypto;
        this.archives = archives;
        this.commands = commands;
        this.auditService = auditService;
        this.verifactuConfigurations = verifactuConfigurations;
        this.fiscalEvents = fiscalEvents;
        this.clock = clock;
        this.defaultDirectory = defaultDirectory;
        this.productImagesDirectory = productImagesDirectory;
        this.documentTemplatesDirectory = documentTemplatesDirectory;
        this.restoreOnlineEnabled = restoreOnlineEnabled;
        this.leaseService = leaseService;
    }

    public BackupService(
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
            Path defaultDirectory,
            Path productImagesDirectory,
            Path documentTemplatesDirectory) {
        this(configurationRepository, executionRepository, installationRepository, storeRepository, userRepository,
                passwordEncoder, keyStore, fileCrypto, archives, commands, auditService, verifactuConfigurations,
                fiscalEvents, clock, defaultDirectory, productImagesDirectory, documentTemplatesDirectory, false, null);
    }

    @Transactional
    public BackupConfigurationItem configure(
            LocalTime time,
            int dailyRetention,
            int monthlyRetention,
            Path directory,
            boolean active,
            String adminPassword) {
        throw new IllegalArgumentException(
                "La configuración nueva requiere una clave de recuperación v2 independiente de ADMIN");
    }

    @Transactional
    public BackupConfigurationItem configure(
            LocalTime time,
            int dailyRetention,
            int monthlyRetention,
            Path directory,
            boolean active,
            String adminPassword,
            String recoverySecret) {
        verifyAdminPassword(adminPassword);
        if (recoverySecret == null || recoverySecret.length() < 16) {
            throw new IllegalArgumentException("La clave de recuperación debe tener al menos 16 caracteres");
        }
        Path destination = (directory == null ? defaultDirectory : directory).toAbsolutePath().normalize();
        keyStore.initializeV2(recoverySecret.toCharArray(), destination);
        Installation installation = currentInstallation();
        BackupSettings configuration = configurationRepository
                .findByInstalacionId(installation.getId())
                .orElseGet(() -> new BackupSettings(
                        installation,
                        time,
                        dailyRetention,
                        monthlyRetention,
                        Map.of("path", destination.toString())));
        configuration.configurar(
                time,
                dailyRetention,
                monthlyRetention,
                Map.of("path", destination.toString()),
                active);
        configurationRepository.save(configuration);
        auditService.record(
                "BACKUP_CONFIGURATION_UPDATED",
                AuditResult.EXITO,
                Map.of("dailyRetention", dailyRetention, "monthlyRetention", monthlyRetention));
        return BackupConfigurationItem.from(configuration);
    }

    @Transactional(readOnly = true)
    public BackupConfigurationItem configuration() {
        return BackupConfigurationItem.from(currentConfiguration());
    }

    @Transactional
    public void initializeDefaultIfMissing() {
        // Backups require an administrator-selected secret. Never bootstrap a
        // key with a known password or silently enable scheduled jobs.
    }

    public ExecutionItem executeNow() {
        BackupSettings configuration = currentConfiguration();
        BackupExecutionLeaseService.Lease lease = null;
        BackupExecution execution;
        if (leaseService != null) {
            lease = leaseService.claim(configuration);
            execution = executionRepository.findById(lease.executionId())
                    .orElseThrow(() -> new IllegalStateException("No se pudo crear la ejecución de backup"));
        } else {
            // Compatibility path for isolated unit tests and old embedders.
            execution = executionRepository.save(new BackupExecution(configuration, Instant.now(clock)));
        }
        Path dump = null;
        Path archive = null;
        Path encrypted = null;
        Path monthlyCopy = null;
        byte[] brk = null;
        boolean executionCommitted = false;
        boolean completionAttempted = false;
        boolean preserveEncrypted = false;
        BackupExecutionLeaseService.LeaseHeartbeat leaseHeartbeat = lease == null
                ? null : leaseService.startHeartbeat(lease);
        try {
            Path destination = destination(configuration);
            Path daily = destination.resolve("daily");
            Files.createDirectories(daily);
            dump = Files.createTempFile(destination, ".tpv-dump-", ".backup");
            archive = Files.createTempFile(destination, ".tpv-archive-", ".zip");
            commands.dump(dump);
            if (leaseHeartbeat != null) leaseHeartbeat.check();
            if (lease != null) leaseService.heartbeat(lease);
            var archiveInfo = archives.create(
                    dump, productImagesDirectory, documentTemplatesDirectory, archive);
            if (leaseHeartbeat != null) leaseHeartbeat.check();
            if (lease != null) leaseService.heartbeat(lease);
            String fileTime = BACKUP_FILE_TIME.format(clock.instant().atZone(clock.getZone()));
            encrypted = daily.resolve("tpv-erp-" + fileTime + "-" + execution.getId() + ".tpvb");
            brk = keyStore.loadForScheduledBackup();
            var info = fileCrypto.encrypt(archive, encrypted, brk);
            if (leaseHeartbeat != null) leaseHeartbeat.check();
            if (lease != null) leaseService.heartbeat(lease);
            Map<String, Object> metadata = Map.of(
                            "path", encrypted.toString(),
                            "plaintextBytes", info.plaintextLength(),
                            "chunks", info.chunkCount(),
                            "databaseBytes", archiveInfo.databaseBytes(),
                            "imageFiles", archiveInfo.imageFiles(),
                            "templateFiles", archiveInfo.templateFiles());
            if (lease != null) {
                completionAttempted = true;
                execution.completar(BackupResult.EXITO, Instant.now(clock), metadata, null);
                leaseService.complete(lease, metadata);
            } else {
                execution.completar(BackupResult.EXITO, Instant.now(clock), metadata, null);
                executionRepository.saveAndFlush(execution);
            }
            executionCommitted = true;
            // Housekeeping is deliberately after confirmation. A worker that
            // loses its lease must never delete a valid backup or replace a
            // confirmed monthly copy.
            try {
                monthlyCopy = createMonthlyCopyIfNeeded(encrypted, destination, configuration);
                enforceRetention(daily, configuration.getRetencionDiaria());
                enforceRetention(destination.resolve("monthly"), configuration.getRetencionMensual());
            } catch (Exception housekeepingFailure) {
                try { auditService.record("BACKUP_HOUSEKEEPING_FAILED", AuditResult.FALLO,
                        Map.of("executionId", execution.getId().toString())); }
                catch (RuntimeException ignored) { }
            }
            auditService.record(
                    "BACKUP_COMPLETED", AuditResult.EXITO, Map.of("path", encrypted.toString()));
        } catch (Exception exception) {
            if (executionCommitted) {
                try { auditService.record("BACKUP_COMPLETION_AUDIT_FAILED", AuditResult.FALLO,
                        Map.of("executionId", execution.getId().toString())); }
                catch (RuntimeException ignored) { }
            } else if (lease != null && completionAttempted) {
                // A transaction/connection error after finishLease may leave the
                // durable outcome unknown. Never delete the candidate in that
                // case; retention will only consider confirmed product names.
                preserveEncrypted = true;
                try { execution.completar(BackupResult.FALLO, Instant.now(clock), null, "LEASE_COMPLETION_UNCERTAIN"); }
                catch (RuntimeException ignored) { }
                try { leaseService.fail(lease, "LEASE_COMPLETION_UNCERTAIN"); }
                catch (RuntimeException ignored) { }
            } else if (lease != null) {
                execution.completar(BackupResult.FALLO, Instant.now(clock), null, safeMessage(exception));
                try { leaseService.fail(lease, safeMessage(exception)); }
                catch (RuntimeException ignored) { /* preserve original failure */ }
            } else {
                execution.completar(BackupResult.FALLO, Instant.now(clock), null, safeMessage(exception));
                executionRepository.saveAndFlush(execution);
            }
            if (!executionCommitted) {
                auditService.record(
                        "BACKUP_FAILED", AuditResult.FALLO, Map.of("reason", safeMessage(exception)));
            }
        } finally {
            if (leaseHeartbeat != null) leaseHeartbeat.close();
            if (brk != null) {
                Arrays.fill(brk, (byte) 0);
            }
            if (dump != null) {
                try {
                    Files.deleteIfExists(dump);
                } catch (Exception ignored) {
                    // The execution result already contains the relevant backup outcome.
                }
            }
            if (archive != null) {
                try {
                    Files.deleteIfExists(archive);
                } catch (Exception ignored) {
                    // The execution result already contains the relevant backup outcome.
                }
            }
            if (!executionCommitted && !preserveEncrypted && encrypted != null) {
                try { Files.deleteIfExists(encrypted); } catch (Exception ignored) { }
            }
            if (!executionCommitted && monthlyCopy != null) {
                try { Files.deleteIfExists(monthlyCopy); } catch (Exception ignored) { }
            }
        }
        return ExecutionItem.from(execution);
    }

    public void restore(Path encryptedBackup, Path recoveryFile, String recoverySecret) {
        if (!restoreOnlineEnabled) {
            throw new IllegalStateException(
                    "La restauracion online esta bloqueada; detenga el backend y use el procedimiento offline documentado");
        }
        if (recoverySecret == null || recoverySecret.length() < 16) {
            throw new IllegalArgumentException("La clave de recuperación v2 no es válida");
        }
        byte[] brk = keyStore.loadForRestore(recoveryFile, recoverySecret.toCharArray());
        Path dump = null;
        Path archive = null;
        try {
            Files.createDirectories(defaultDirectory.toAbsolutePath());
            dump = Files.createTempFile(defaultDirectory.toAbsolutePath(), ".tpv-restore-", ".backup");
            archive = Files.createTempFile(defaultDirectory.toAbsolutePath(), ".tpv-restore-", ".zip");
            fileCrypto.decrypt(encryptedBackup, archive, brk);
            Path stagingRoot = Files.createTempDirectory(defaultDirectory.toAbsolutePath(), ".tpv-restore-staging-");
            Path stagedImages = stagingRoot.resolve("images");
            Path stagedTemplates = stagingRoot.resolve("templates");
            try {
                archives.extractToStaging(archive, dump, stagedImages, stagedTemplates);
                commands.restore(dump);
                archives.replaceTrees(
                        stagedImages, productImagesDirectory, stagedTemplates, documentTemplatesDirectory);
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "La base de datos puede haberse restaurado, pero no se pudieron aplicar los arboles; revise el estado", exception);
            } finally {
                try (var paths = Files.walk(stagingRoot)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                } catch (Exception ignored) { }
            }
            recordFiscalRestorationEvent();
            auditService.record(
                    "BACKUP_RESTORED",
                    AuditResult.EXITO,
                    Map.of("path", encryptedBackup.toAbsolutePath().toString()));
        } catch (Exception exception) {
            auditService.record(
                    "BACKUP_RESTORE_FAILED",
                    AuditResult.FALLO,
                    Map.of("reason", safeMessage(exception)));
            throw new IllegalStateException("No se pudo restaurar el backup", exception);
        } finally {
            Arrays.fill(brk, (byte) 0);
            if (dump != null) {
                try {
                    Files.deleteIfExists(dump);
                } catch (Exception ignored) {
                    // The original encrypted backup remains untouched.
                }
            }
            if (archive != null) {
                try {
                    Files.deleteIfExists(archive);
                } catch (Exception ignored) {
                    // The original encrypted backup remains untouched.
                }
            }
        }
    }

    /**
     * A managed restore is an official NO VERI*FACTU event. It is deliberately
     * executed only after PostgreSQL has restored the fiscal chain, so event 07
     * becomes the first new immutable record in the restored installation.
     */
    private void recordFiscalRestorationEvent() {
        var installation = currentInstallation();
        verifactuConfigurations.findAllByCurrentMode(FiscalMode.NO_VERIFACTU)
                .forEach(configuration -> fiscalEvents.create(
                        configuration.getCompanyId(), installation.getId(),
                        FiscalMode.NO_VERIFACTU, FiscalEventType.BACKUP_RESTORED,
                        "BACKUP_RESTORED"));
    }

    @Transactional(readOnly = true)
    public List<ExecutionItem> history() {
        BackupSettings configuration = currentConfiguration();
        return executionRepository.findTop100ByConfiguracionIdOrderByIniciadaEnDesc(configuration.getId())
                .stream()
                .map(ExecutionItem::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isDue() {
        BackupSettings configuration = currentConfiguration();
        if (!configuration.isActiva()) {
            return false;
        }
        ZoneId zone = ZoneId.systemDefault();
        Instant startOfToday = LocalDate.now(clock).atStartOfDay(zone).toInstant();
        boolean ranToday = executionRepository
                .existsByConfiguracionIdAndResultadoAndIniciadaEnGreaterThanEqual(
                        configuration.getId(), BackupResult.EXITO, startOfToday);
        return !ranToday && !LocalTime.now(clock).isBefore(configuration.getHora());
    }

    private void verifyAdminPassword(String password) {
        UserAccount admin = storeRepository.findAll().stream().findFirst()
                .flatMap(store -> userRepository.findByTiendaIdAndNombre(store.getId(), "ADMIN"))
                .or(() -> userRepository.findAll().stream()
                        .filter(user -> "ADMIN".equals(user.getNombre()))
                        .findFirst())
                .orElseThrow(() -> new IllegalStateException("El usuario ADMIN no existe"));
        if (password == null || !passwordEncoder.matches(password, admin.getPasswordHash())) {
            throw new IllegalArgumentException("La contrasena ADMIN no es valida");
        }
    }

    private BackupSettings currentConfiguration() {
        return configurationRepository.findByInstalacionId(currentInstallation().getId())
                .orElseThrow(() -> new IllegalStateException("El backup todavia no esta configurado"));
    }

    private Installation currentInstallation() {
        return installationRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("La instalacion no esta inicializada"));
    }

    private Path destination(BackupSettings configuration) {
        return Path.of(configuration.getDestino().get("path").toString());
    }

    private Path createMonthlyCopyIfNeeded(
            Path dailyBackup,
            Path destination,
            BackupSettings configuration) throws Exception {
        LocalDate today = LocalDate.now(clock);
        if (today.getDayOfMonth() != 1) {
            return null;
        }
        Path monthly = destination.resolve("monthly");
        Files.createDirectories(monthly);
        Path monthlyBackup = monthly.resolve(
                "tpv-erp-" + today.getYear() + "-" + "%02d".formatted(today.getMonthValue()) + ".tpvb");
        Path temporary = Files.createTempFile(monthly, ".tpv-monthly-", ".tmp");
        try {
            Files.copy(dailyBackup, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(
                        temporary,
                        monthlyBackup,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                throw new IllegalStateException("El destino mensual no admite promocion atomica", exception);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        enforceRetention(monthly, configuration.getRetencionMensual());
        return monthlyBackup;
    }

    private void enforceRetention(Path directory, int retention) throws Exception {
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("tpv-erp-"))
                    .filter(path -> path.getFileName().toString().endsWith(".tpvb"))
                    .sorted(Comparator.comparingLong(this::lastModified).reversed())
                    .toList();
        }
        for (int index = retention; index < files.size(); index++) {
            Files.deleteIfExists(files.get(index));
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception exception) {
            return Long.MIN_VALUE;
        }
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    public record BackupConfigurationItem(
            LocalTime time,
            int dailyRetention,
            int monthlyRetention,
            boolean active,
            String directory) {
        static BackupConfigurationItem from(BackupSettings configuration) {
            return new BackupConfigurationItem(
                    configuration.getHora(),
                    configuration.getRetencionDiaria(),
                    configuration.getRetencionMensual(),
                    configuration.isActiva(),
                    configuration.getDestino().get("path").toString());
        }
    }

    public record ExecutionItem(
            UUID id,
            Instant startedAt,
            Instant finishedAt,
            BackupResult result,
            Map<String, Object> metadata,
            String errorReason) {
        static ExecutionItem from(BackupExecution execution) {
            return new ExecutionItem(
                    execution.getId(),
                    execution.getIniciadaEn(),
                    execution.getFinalizadaEn(),
                    execution.getResultado(),
                    execution.getMetadata(),
                    execution.getMotivoError());
        }
    }
}

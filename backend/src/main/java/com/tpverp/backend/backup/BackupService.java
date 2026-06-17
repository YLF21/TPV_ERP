package com.tpverp.backend.backup;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.audit.ResultadoAuditoria;
import com.tpverp.backend.backup.application.BackupFileCrypto;
import com.tpverp.backend.backup.application.BackupArchiveService;
import com.tpverp.backend.backup.application.BackupKeyStore;
import com.tpverp.backend.backup.application.PostgreSqlBackupCommands;
import com.tpverp.backend.installation.Instalacion;
import com.tpverp.backend.installation.InstalacionRepository;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.organization.TiendaRepository;
import com.tpverp.backend.security.domain.Usuario;
import com.tpverp.backend.security.domain.UsuarioRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class BackupService {

    private final ConfiguracionBackupRepository configurationRepository;
    private final EjecucionBackupRepository executionRepository;
    private final InstalacionRepository installationRepository;
    private final TiendaRepository storeRepository;
    private final UsuarioRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final BackupKeyStore keyStore;
    private final BackupFileCrypto fileCrypto;
    private final BackupArchiveService archives;
    private final PostgreSqlBackupCommands commands;
    private final AuditService auditService;
    private final Clock clock;
    private final Path defaultDirectory;
    private final Path productImagesDirectory;

    public BackupService(
            ConfiguracionBackupRepository configurationRepository,
            EjecucionBackupRepository executionRepository,
            InstalacionRepository installationRepository,
            TiendaRepository storeRepository,
            UsuarioRepository userRepository,
            PasswordEncoder passwordEncoder,
            BackupKeyStore keyStore,
            BackupFileCrypto fileCrypto,
            BackupArchiveService archives,
            PostgreSqlBackupCommands commands,
            AuditService auditService,
            Clock clock,
            Path defaultDirectory,
            Path productImagesDirectory) {
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
        this.clock = clock;
        this.defaultDirectory = defaultDirectory;
        this.productImagesDirectory = productImagesDirectory;
    }

    @Transactional
    public BackupConfigurationItem configure(
            LocalTime time,
            int dailyRetention,
            int monthlyRetention,
            Path directory,
            boolean active,
            String adminPassword) {
        verifyAdminPassword(adminPassword);
        Path destination = (directory == null ? defaultDirectory : directory).toAbsolutePath().normalize();
        keyStore.initialize(adminPassword.toCharArray(), destination);
        Instalacion installation = currentInstallation();
        ConfiguracionBackup configuration = configurationRepository
                .findByInstalacionId(installation.getId())
                .orElseGet(() -> new ConfiguracionBackup(
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
                ResultadoAuditoria.EXITO,
                Map.of("dailyRetention", dailyRetention, "monthlyRetention", monthlyRetention));
        return BackupConfigurationItem.from(configuration);
    }

    @Transactional(readOnly = true)
    public BackupConfigurationItem configuration() {
        return BackupConfigurationItem.from(currentConfiguration());
    }

    @Transactional
    public void initializeDefaultIfMissing() {
        Instalacion installation = currentInstallation();
        if (configurationRepository.findByInstalacionId(installation.getId()).isEmpty()) {
            configure(LocalTime.NOON, 30, 72, defaultDirectory, true, "0000");
        }
    }

    @Transactional
    public ExecutionItem executeNow() {
        ConfiguracionBackup configuration = currentConfiguration();
        EjecucionBackup execution = executionRepository.save(
                new EjecucionBackup(configuration, Instant.now(clock)));
        Path dump = null;
        Path archive = null;
        byte[] brk = null;
        try {
            Path destination = destination(configuration);
            Path daily = destination.resolve("daily");
            Files.createDirectories(daily);
            dump = Files.createTempFile(destination, ".tpv-dump-", ".backup");
            archive = Files.createTempFile(destination, ".tpv-archive-", ".zip");
            commands.dump(dump);
            var archiveInfo = archives.create(dump, productImagesDirectory, archive);
            String date = LocalDate.now(clock).toString();
            Path encrypted = daily.resolve("tpv-erp-" + date + ".tpvb");
            brk = keyStore.loadForScheduledBackup();
            var info = fileCrypto.encrypt(archive, encrypted, brk);
            createMonthlyCopyIfNeeded(encrypted, destination, configuration);
            enforceRetention(daily, configuration.getRetencionDiaria());
            enforceRetention(destination.resolve("monthly"), configuration.getRetencionMensual());
            execution.completar(
                    ResultadoBackup.EXITO,
                    Instant.now(clock),
                    Map.of(
                            "path", encrypted.toString(),
                            "plaintextBytes", info.plaintextLength(),
                            "chunks", info.chunkCount(),
                            "databaseBytes", archiveInfo.databaseBytes(),
                            "imageFiles", archiveInfo.imageFiles()),
                    null);
            auditService.record(
                    "BACKUP_COMPLETED", ResultadoAuditoria.EXITO, Map.of("path", encrypted.toString()));
        } catch (Exception exception) {
            execution.completar(
                    ResultadoBackup.FALLO,
                    Instant.now(clock),
                    null,
                    safeMessage(exception));
            auditService.record(
                    "BACKUP_FAILED", ResultadoAuditoria.FALLO, Map.of("reason", safeMessage(exception)));
        } finally {
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
        }
        return ExecutionItem.from(execution);
    }

    public void restore(Path encryptedBackup, Path recoveryFile, String adminPassword) {
        verifyAdminPassword(adminPassword);
        byte[] brk = keyStore.loadForRestore(recoveryFile, adminPassword.toCharArray());
        Path dump = null;
        Path archive = null;
        try {
            Files.createDirectories(defaultDirectory.toAbsolutePath());
            dump = Files.createTempFile(defaultDirectory.toAbsolutePath(), ".tpv-restore-", ".backup");
            archive = Files.createTempFile(defaultDirectory.toAbsolutePath(), ".tpv-restore-", ".zip");
            fileCrypto.decrypt(encryptedBackup, archive, brk);
            archives.restore(archive, dump, productImagesDirectory);
            commands.restore(dump);
            auditService.record(
                    "BACKUP_RESTORED",
                    ResultadoAuditoria.EXITO,
                    Map.of("path", encryptedBackup.toAbsolutePath().toString()));
        } catch (Exception exception) {
            auditService.record(
                    "BACKUP_RESTORE_FAILED",
                    ResultadoAuditoria.FALLO,
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

    @Transactional(readOnly = true)
    public List<ExecutionItem> history() {
        ConfiguracionBackup configuration = currentConfiguration();
        return executionRepository.findByConfiguracionIdOrderByIniciadaEnDesc(configuration.getId())
                .stream()
                .map(ExecutionItem::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isDue() {
        ConfiguracionBackup configuration = currentConfiguration();
        if (!configuration.isActiva()) {
            return false;
        }
        ZoneId zone = ZoneId.systemDefault();
        Instant startOfToday = LocalDate.now(clock).atStartOfDay(zone).toInstant();
        boolean ranToday = executionRepository
                .findByConfiguracionIdOrderByIniciadaEnDesc(configuration.getId())
                .stream()
                .anyMatch(value -> !value.getIniciadaEn().isBefore(startOfToday));
        return !ranToday && !LocalTime.now(clock).isBefore(configuration.getHora());
    }

    private void verifyAdminPassword(String password) {
        Tienda store = storeRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("La tienda no esta inicializada"));
        Usuario admin = userRepository.findByTiendaIdAndNombre(store.getId(), "ADMIN")
                .orElseThrow(() -> new IllegalStateException("El usuario ADMIN no existe"));
        if (password == null || !passwordEncoder.matches(password, admin.getPasswordHash())) {
            throw new IllegalArgumentException("La contrasena ADMIN no es valida");
        }
    }

    private ConfiguracionBackup currentConfiguration() {
        return configurationRepository.findByInstalacionId(currentInstallation().getId())
                .orElseThrow(() -> new IllegalStateException("El backup todavia no esta configurado"));
    }

    private Instalacion currentInstallation() {
        return installationRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("La instalacion no esta inicializada"));
    }

    private Path destination(ConfiguracionBackup configuration) {
        return Path.of(configuration.getDestino().get("path").toString());
    }

    private void createMonthlyCopyIfNeeded(
            Path dailyBackup,
            Path destination,
            ConfiguracionBackup configuration) throws Exception {
        LocalDate today = LocalDate.now(clock);
        if (today.getDayOfMonth() != 1) {
            return;
        }
        Path monthly = destination.resolve("monthly");
        Files.createDirectories(monthly);
        Files.copy(
                dailyBackup,
                monthly.resolve("tpv-erp-" + today.getYear() + "-" + "%02d".formatted(today.getMonthValue()) + ".tpvb"),
                StandardCopyOption.REPLACE_EXISTING);
        enforceRetention(monthly, configuration.getRetencionMensual());
    }

    private void enforceRetention(Path directory, int retention) throws Exception {
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(Files::isRegularFile)
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
        static BackupConfigurationItem from(ConfiguracionBackup configuration) {
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
            ResultadoBackup result,
            Map<String, Object> metadata,
            String errorReason) {
        static ExecutionItem from(EjecucionBackup execution) {
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

package com.tpverp.backend.backup;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.backup.application.BackupFileCrypto;
import com.tpverp.backend.backup.application.BackupSnapshotService;
import com.tpverp.backend.backup.application.BackupArchiveService;
import com.tpverp.backend.backup.application.BackupKeyStore;
import com.tpverp.backend.backup.application.PostgreSqlBackupCommands;
import com.tpverp.backend.backup.cloud.CloudBackupDescriptor;
import com.tpverp.backend.backup.cloud.CloudBackupQueue;
import com.tpverp.backend.backup.cloud.CloudBackupStatus;
import com.tpverp.backend.disasterrecovery.RecoverySecretsCodec;
import com.tpverp.backend.servertransfer.ServerTransferSecrets;
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
    private BackupSnapshotService snapshots;
    private String applicationVersion = "UNKNOWN";
    private Map<String, Object> release = Map.of();
    private CloudBackupQueue cloudQueue;
    private ServerTransferSecrets portableSecrets;
    private CloudBackupRepairService cloudRepair;
    private Path workspaceDirectory;

    public void configureWorkspace(Path directory) {
        this.workspaceDirectory = java.util.Objects.requireNonNull(directory).toAbsolutePath().normalize();
    }

    public void configureCloud(CloudBackupQueue queue, ServerTransferSecrets secrets, CloudBackupRepairService repair) {
        this.cloudQueue = java.util.Objects.requireNonNull(queue);
        this.portableSecrets = java.util.Objects.requireNonNull(secrets);
        this.cloudRepair = java.util.Objects.requireNonNull(repair);
    }

    public void configureSnapshots(BackupSnapshotService snapshots, String applicationVersion) {
        this.snapshots = java.util.Objects.requireNonNull(snapshots);
        this.applicationVersion = applicationVersion;
    }

    public void configureRelease(com.tpverp.backend.verifactu.FiscalReleaseManifest manifest) {
        release = Map.of("releaseId", manifest.releaseId(), "databaseSchemaVersion", manifest.schemaVersion(),
                "releaseSequence", manifest.releaseSequence(), "buildSequence", manifest.buildSequence());
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
        return configurationRepository.findByInstalacionId(currentInstallation().getId())
                .map(BackupConfigurationItem::from)
                .orElseGet(() -> new BackupConfigurationItem(LocalTime.NOON, 30, 72, false,
                        defaultDirectory.toAbsolutePath().normalize().toString(), false));
    }

    @Transactional
    public void initializeDefaultIfMissing() {
        // Backups require an administrator-selected secret. Never bootstrap a
        // key with a known password or silently enable scheduled jobs.
    }

    public ExecutionItem executeNow() {
        return executePrepared(prepare("MANUAL"));
    }

    public Prepared prepare(String origin) {
        BackupSettings configuration = currentConfiguration();
        BackupExecutionLeaseService.Lease lease = null;
        BackupExecution execution;
        if (leaseService != null) {
            lease = leaseService.claim(configuration, origin);
            execution = executionRepository.findById(lease.executionId())
                    .orElseThrow(() -> new IllegalStateException("No se pudo crear la ejecución de backup"));
        } else {
            // Compatibility path for isolated unit tests and old embedders.
            execution = new BackupExecution(configuration, Instant.now(clock));
            execution.setOrigin(origin);
            execution = executionRepository.save(execution);
        }
        return new Prepared(configuration, execution, lease, Map.of());
    }

    public Prepared prepareTransfer(Map<String, Object> transfer) {
        var prepared = prepare("TRANSFER");
        return new Prepared(prepared.configuration(), prepared.execution(), prepared.lease(), Map.copyOf(transfer));
    }

    public void reject(Prepared prepared) {
        if (prepared.lease() != null) leaseService.fail(prepared.lease(), "No se pudo iniciar el proceso de copia");
    }

    public ExecutionItem executePrepared(Prepared prepared) {
        BackupSettings configuration = prepared.configuration();
        BackupExecution execution = prepared.execution();
        BackupExecutionLeaseService.Lease lease = prepared.lease();
        Path dump = null;
        Path archive = null;
        Path encrypted = null;
        Path monthlyCopy = null;
        byte[] brk = null;
        boolean executionCommitted = false;
        boolean completionAttempted = false;
        boolean preserveEncrypted = false;
        boolean ownsEncrypted = false;
        byte[] portableCapsule = null;
        BackupExecutionLeaseService.LeaseHeartbeat leaseHeartbeat = lease == null
                ? null : leaseService.startHeartbeat(lease);
        try {
            Path destination = destination(configuration);
            Path daily = destination.resolve("daily");
            Files.createDirectories(daily);
            // The selected output folder may be a shared drive; clear database/ZIP material stays private.
            Path workspace = workspaceDirectory == null ? destination : workspaceDirectory;
            for (Path parent = workspace; parent != null; parent = parent.getParent()) {
                if (Files.exists(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    var attributes = Files.readAttributes(parent, java.nio.file.attribute.BasicFileAttributes.class,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isSymbolicLink() || attributes.isOther())
                        throw new IllegalStateException("El directorio temporal de backup no puede contener enlaces");
                }
            }
            Files.createDirectories(workspace);
            if (Files.getFileStore(workspace).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(workspace, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            dump = Files.createTempFile(workspace, ".tpv-dump-", ".backup");
            archive = Files.createTempFile(workspace, ".tpv-archive-", ".zip");
            phase(lease, "DATABASE");
            brk = keyStore.loadForScheduledBackup();
            Map<String, Object> manifest = null;
            BackupArchiveService.BackupArchiveInfo archiveInfo;
            if (snapshots != null) {
                var encryptedPortable = new java.util.concurrent.atomic.AtomicReference<byte[]>();
                byte[] backupKey = brk;
                UUID installationId = currentInstallation().getId();
                try (var capture = snapshots.capture(commands, dump, installationId, applicationVersion, (connection, values) -> {
                    if (portableSecrets == null) return;
                    try (var portable = portableSecrets.capturePortable(connection)) {
                        encryptedPortable.set(RecoverySecretsCodec.seal(portable.material(), backupKey,
                                installationId, execution.getId()));
                        values.put("portableSecretsVersion", RecoverySecretsCodec.VERSION);
                        values.put("portableSecretsComplete", portable.complete());
                        values.put("portableSecretsWarnings", portable.warnings());
                    }
                })) {
                    portableCapsule = encryptedPortable.get();
                    manifest = new java.util.LinkedHashMap<>(capture.manifest());
                    manifest.putAll(release);
                    manifest.put("executionId", execution.getId().toString());
                    if (execution.getSequenceNumber() != null) manifest.put("sequenceNumber", execution.getSequenceNumber());
                    manifest.put("origin", execution.getOrigin());
                    if (!prepared.transfer().isEmpty()) manifest.put("serverTransfer", prepared.transfer());
                    phase(lease, "ARCHIVE");
                    archiveInfo = archives.create(dump, productImagesDirectory, documentTemplatesDirectory, archive, manifest, portableCapsule);
                }
            } else {
                // Retained for unit-test embedders. Production always configures the snapshot service.
                commands.dump(dump);
                archiveInfo = archives.create(dump, productImagesDirectory, documentTemplatesDirectory, archive);
            }
            if (leaseHeartbeat != null) leaseHeartbeat.check();
            if (lease != null) leaseService.heartbeat(lease);
            String fileTime = BACKUP_FILE_TIME.format(clock.instant().atZone(clock.getZone()));
            encrypted = daily.resolve(execution.getSequenceNumber() == null
                    ? "tpv-erp-" + fileTime + "-" + execution.getId() + ".tpvb"
                    : "%06d.tpvb".formatted(execution.getSequenceNumber()));
            long needed = Math.addExact(Math.multiplyExact(Files.size(archive), 2), 1024L * 1024);
            if (Files.getFileStore(destination).getUsableSpace() < needed) {
                throw new IllegalStateException("No hay espacio suficiente para cifrar y verificar la copia");
            }
            // Reserve a new name. Neither encryption nor failure cleanup may replace an older backup.
            if (Files.exists(destination.resolve("monthly").resolve(encrypted.getFileName()), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("El ID de copia ya existe en la carpeta mensual; seleccione otra carpeta de destino");
            }
            Files.createFile(encrypted);
            ownsEncrypted = true;
            phase(lease, "ENCRYPTING");
            var info = fileCrypto.encrypt(archive, encrypted, brk);
            phase(lease, "VERIFYING");
            fileCrypto.verify(encrypted, brk);
            if (leaseHeartbeat != null) leaseHeartbeat.check();
            if (lease != null) leaseService.heartbeat(lease);
            Map<String, Object> metadata = new java.util.LinkedHashMap<>(Map.of(
                            "path", encrypted.toString(),
                            "plaintextBytes", info.plaintextLength(),
                            "chunks", info.chunkCount(),
                            "formatVersion", info.version(),
                            "databaseBytes", archiveInfo.databaseBytes(),
                            "imageFiles", archiveInfo.imageFiles(),
                            "templateFiles", archiveInfo.templateFiles(),
                            "sizeBytes", Files.size(encrypted),
                            "verifiedAt", Instant.now(clock).toString()));
            if (manifest != null) metadata.put("manifest", manifest);
            metadata.put("monthlyPath", destination.resolve("monthly").resolve(encrypted.getFileName()).toString());
            CloudBackupDescriptor cloudDescriptor = null;
            byte[] wrappedRecovery = null;
            if (cloudQueue != null) {
                metadata.put("cloudRequired", true);
                try {
                    wrappedRecovery = keyStore.exportWrappedRecoveryKey();
                    cloudDescriptor = new CloudBackupDescriptor(execution.getId(), currentInstallation().getId(),
                            execution.getSequenceNumber(), Instant.parse(manifest.get("capturedAt").toString()),
                            applicationVersion, info.version(), portableCapsule == null ? 0 : RecoverySecretsCodec.VERSION,
                            Files.size(encrypted), CloudBackupRepairService.sha256(encrypted),
                            CloudBackupRepairService.sha256(wrappedRecovery), 2);
                    metadata.put("cloudDescriptor", CloudBackupRepairService.descriptorMetadata(cloudDescriptor));
                    metadata.put("cloudRecoveryKey", java.util.Base64.getEncoder().encodeToString(wrappedRecovery));
                } catch (Exception cloudPreparationFailure) {
                    metadata.put("cloudPreparationError", "CLOUD_BACKUP_PREPARATION_FAILED");
                }
            }
            if (lease != null) {
                completionAttempted = true;
                execution.completar(BackupResult.EXITO, Instant.now(clock), metadata, null);
                leaseService.complete(lease, metadata);
            } else {
                execution.completar(BackupResult.EXITO, Instant.now(clock), metadata, null);
                executionRepository.saveAndFlush(execution);
            }
            executionCommitted = true;
            boolean cloudQueued = cloudQueue == null;
            if (cloudQueue != null && cloudDescriptor != null) {
                try {
                    cloudQueue.enqueue(cloudDescriptor, encrypted, wrappedRecovery);
                    cloudQueued = true;
                } catch (RuntimeException cloudSpoolFailure) {
                    // The verified local copy remains successful and is repaired from its durable metadata.
                    // Do not run retention while its independent cloud spool is still missing.
                }
            }
            if (wrappedRecovery != null) Arrays.fill(wrappedRecovery, (byte) 0);
            // Housekeeping is deliberately after confirmation. A worker that
            // loses its lease must never delete a valid backup or replace a
            // confirmed monthly copy.
            try {
                monthlyCopy = createMonthlyCopyIfNeeded(encrypted, destination, configuration);
                if (cloudQueued) enforceRetention(daily, configuration.getRetencionDiaria());
                enforceRetention(destination.resolve("monthly"), configuration.getRetencionMensual());
            } catch (Exception housekeepingFailure) {
                try { auditService.record("BACKUP_HOUSEKEEPING_FAILED", AuditResult.FALLO,
                        Map.of("executionId", execution.getId().toString())); }
                catch (RuntimeException ignored) { }
            }
            if (!execution.getOrigin().equals("TRANSFER")) auditService.record(
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
            if (!executionCommitted && !execution.getOrigin().equals("TRANSFER")) {
                auditService.record(
                        "BACKUP_FAILED", AuditResult.FALLO, Map.of("reason", safeMessage(exception)));
            }
        } finally {
            if (leaseHeartbeat != null) leaseHeartbeat.close();
            if (brk != null) {
                Arrays.fill(brk, (byte) 0);
            }
            if (portableCapsule != null) Arrays.fill(portableCapsule, (byte) 0);
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
                if (ownsEncrypted) {
                    try { Files.deleteIfExists(encrypted); } catch (Exception ignored) { }
                }
            }
            if (!executionCommitted && monthlyCopy != null) {
                try { Files.deleteIfExists(monthlyCopy); } catch (Exception ignored) { }
            }
        }
        return withCloud(ExecutionItem.from(execution));
    }

    /** Exceptional recovery is maintenance-only; HTTP and runtime feature flags cannot enable it. */
    public void restore(Path encryptedBackup, Path recoveryFile, String recoverySecret) {
        throw new IllegalStateException("La restauración libre no está disponible; utilice el traslado controlado del servidor");
    }

    private void phase(BackupExecutionLeaseService.Lease lease, String phase) {
        if (lease != null) leaseService.progress(lease, phase);
    }

    public record Prepared(BackupSettings configuration, BackupExecution execution,
            BackupExecutionLeaseService.Lease lease, Map<String, Object> transfer) { }

    @Transactional(readOnly = true)
    public List<ExecutionItem> history() {
        BackupSettings configuration = currentConfiguration();
        return executionRepository.findTop100ByConfiguracionIdOrderByIniciadaEnDesc(configuration.getId())
                .stream()
                .map(value -> withCloud(ExecutionItem.from(value)))
                .toList();
    }

    @Transactional(readOnly = true)
    public HistoryPage history(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Paginación inválida");
        var configured = configuration();
        if (!configured.configured()) return new HistoryPage(List.of(), 0, page, size, null, null, false, false);
        var settings = currentConfiguration();
        var rows = executionRepository.findByConfiguracionIdOrderBySequenceNumberDesc(settings.getId(),
                org.springframework.data.domain.PageRequest.of(page, size));
        Instant last = executionRepository.findTopByConfiguracionIdAndResultadoOrderByFinalizadaEnDesc(
                settings.getId(), BackupResult.EXITO).map(BackupExecution::getFinalizadaEn).orElse(null);
        Instant now = Instant.now(clock);
        Instant next = null;
        if (settings.isActiva()) {
            ZoneId zone = backupZone();
            LocalDate today = now.atZone(zone).toLocalDate();
            var scheduled = today.atTime(settings.getHora()).atZone(zone);
            Instant start = today.atStartOfDay(zone).toInstant();
            boolean succeeded = executionRepository.existsByConfiguracionIdAndResultadoAndIniciadaEnGreaterThanEqual(
                    settings.getId(), BackupResult.EXITO, start);
            if (succeeded) scheduled = scheduled.plusDays(1);
            next = scheduled.toInstant().isBefore(now) ? now.plusSeconds(60) : scheduled.toInstant();
        }
        var cloudStates = cloudQueue == null ? Map.<UUID, CloudBackupStatus>of()
                : cloudQueue.statuses(rows.getContent().stream().map(BackupExecution::getId).toList());
        return new HistoryPage(rows.getContent().stream().map(ExecutionItem::from)
                .map(value -> value.withCloud(cloudStates.getOrDefault(value.id(), pendingCloud(value.metadata())))).toList(), rows.getTotalElements(),
                page, size, last, next, settings.isActiva() && (last == null || last.isBefore(now.minusSeconds(86_400))), true);
    }

    @Transactional(readOnly = true)
    public ExecutionItem detail(UUID id) {
        var row = executionRepository.findById(id).filter(value -> value.configurationId().equals(currentConfiguration().getId()))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        return withCloud(ExecutionItem.from(row));
    }

    public CloudBackupStatus retryCloud(UUID id) {
        detail(id); // Enforces ownership of this installation's backup.
        if (cloudQueue == null) throw new IllegalStateException("El envío a SaaS no está configurado");
        if (cloudQueue.status(id) == null) {
            cloudRepair.requestRepair(id);
            return new CloudBackupStatus("PENDING", 0, null, Instant.now(clock), null);
        }
        return cloudQueue.retry(id);
    }

    public void ensureCloudCopy(Path file) {
        if (cloudQueue == null) throw new IllegalStateException("La copia final debe enviarse a SaaS antes del traslado");
        UUID id = executionRepository.findIdByArtifactPath(file.toAbsolutePath().toString())
                .orElseThrow(() -> new IllegalStateException("No se encontró la copia final del traslado"));
        if (cloudQueue.status(id) == null) {
            try { cloudRepair.repairOne(id); }
            catch (Exception failure) { throw new IllegalStateException("No se pudo preparar el envío final a SaaS", failure); }
        }
        cloudQueue.retry(id);
        if (!cloudRepair.repairAllForTransfer() || !cloudQueue.runAllForTransfer() || !cloudQueue.runOneForTransfer(id)) {
            throw new IllegalStateException("La copia final sigue pendiente de envío a SaaS; el origen permanece bloqueado");
        }
    }

    private ExecutionItem withCloud(ExecutionItem item) {
        return item.withCloud(cloudQueue == null ? null : java.util.Optional.ofNullable(cloudQueue.status(item.id()))
                .orElseGet(() -> pendingCloud(item.metadata())));
    }

    private static CloudBackupStatus pendingCloud(Map<String, Object> metadata) {
        if (Boolean.TRUE.equals(metadata.get("cloudAlreadyStored"))) {
            return new CloudBackupStatus("SENT", 0, null, null, null);
        }
        if (Boolean.TRUE.equals(metadata.get("restoreSourceUnavailable"))) {
            return new CloudBackupStatus("RETIRED", 0, null, null, "SOURCE_SPOOL_NOT_RECOVERED");
        }
        if (!Boolean.TRUE.equals(metadata.get("cloudRequired"))) return null;
        return new CloudBackupStatus(metadata.containsKey("cloudPreparationError") ? "FAILED" : "PENDING", 0,
                null, null, metadata.getOrDefault("cloudPreparationError", "CLOUD_BACKUP_QUEUING_PENDING").toString());
    }

    public record HistoryPage(List<ExecutionItem> items, long total, int page, int size,
            Instant lastSuccessfulAt, Instant nextScheduledAt, boolean overdue, boolean configured) { }

    @Transactional(readOnly = true)
    public boolean isDue() {
        BackupSettings configuration = currentConfiguration();
        if (!configuration.isActiva()) {
            return false;
        }
        ZoneId zone = backupZone();
        Instant now = clock.instant();
        LocalDate today = now.atZone(zone).toLocalDate();
        Instant startOfToday = today.atStartOfDay(zone).toInstant();
        boolean ranToday = executionRepository
                .existsByConfiguracionIdAndResultadoAndIniciadaEnGreaterThanEqual(
                        configuration.getId(), BackupResult.EXITO, startOfToday);
        return !ranToday && !now.isBefore(today.atTime(configuration.getHora()).atZone(zone).toInstant());
    }

    private ZoneId backupZone() {
        var stores = storeRepository.findAll();
        // Local installations have one store. Retain the injected clock for
        // bootstrap and standalone tools that do not yet have a store.
        return stores.size() == 1 ? ZoneId.of(stores.getFirst().getTimezone()) : clock.getZone();
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
        ZoneId zone = backupZone();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Path monthly = destination.resolve("monthly");
        Files.createDirectories(monthly);
        try (var files = Files.list(monthly)) {
            boolean exists = files.filter(this::isBackupFile).anyMatch(path ->
                    java.time.YearMonth.from(Instant.ofEpochMilli(lastModified(path)).atZone(zone))
                            .equals(java.time.YearMonth.from(today)));
            if (exists) return null;
        }
        Path monthlyBackup = monthly.resolve(dailyBackup.getFileName());
        boolean promoted = false;
        boolean ownsMonthly = false;
        Path temporary = Files.createTempFile(monthly, ".tpv-monthly-", ".tmp");
        try {
            Files.createFile(monthlyBackup);
            ownsMonthly = true;
            Files.copy(dailyBackup, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(
                        temporary,
                        monthlyBackup,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                promoted = true;
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                throw new IllegalStateException("El destino mensual no admite promocion atomica", exception);
            }
        } finally {
            Files.deleteIfExists(temporary);
            if (ownsMonthly && !promoted) Files.deleteIfExists(monthlyBackup);
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
            files = stream.filter(this::isBackupFile)
                    .sorted(Comparator.comparingLong(this::lastModified).reversed())
                    .toList();
        }
        for (int index = retention; index < files.size(); index++) {
            Path candidate = files.get(index);
            if (cloudQueue == null || !executionRepository.needsCloudSpool(candidate.toString())) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    private boolean isBackupFile(Path path) {
        String name = path.getFileName().toString();
        return Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && (name.matches("[0-9]{6,}\\.tpvb") || name.matches("tpv-erp-[A-Za-z0-9-]+\\.tpvb"));
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception exception) {
            return Long.MIN_VALUE;
        }
    }

    private String safeMessage(Exception exception) {
        if (exception instanceof java.nio.file.AccessDeniedException) {
            return "No hay permisos para leer o escribir en la carpeta de copias";
        }
        if (exception instanceof java.nio.file.FileAlreadyExistsException) {
            return "El archivo de copia ya existe; se ha conservado sin sobrescribirlo";
        }
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    public record BackupConfigurationItem(
            LocalTime time,
            int dailyRetention,
            int monthlyRetention,
            boolean active,
            String directory,
            boolean configured) {
        static BackupConfigurationItem from(BackupSettings configuration) {
            return new BackupConfigurationItem(
                    configuration.getHora(),
                    configuration.getRetencionDiaria(),
                    configuration.getRetencionMensual(),
                    configuration.isActiva(),
                    configuration.getDestino().get("path").toString(), true);
        }
    }

    public record ExecutionItem(
            UUID id,
            Instant startedAt,
            Instant finishedAt,
            BackupResult result,
            Map<String, Object> metadata,
            String errorReason,
            Long sequenceNumber,
            Long sizeBytes,
            boolean available,
            String origin,
            String phase,
            CloudBackupStatus cloud) {
        ExecutionItem withCloud(CloudBackupStatus value) {
            return new ExecutionItem(id, startedAt, finishedAt, result, metadata, errorReason,
                    sequenceNumber, sizeBytes, available, origin, phase, value);
        }
        static ExecutionItem from(BackupExecution execution) {
            Map<String, Object> metadata = execution.getMetadata();
            Path file = availablePath(metadata);
            Long size = metadata.get("sizeBytes") instanceof Number number ? number.longValue() : null;
            if (file != null) {
                try { size = Files.size(file); } catch (java.io.IOException ignored) { file = null; }
            }
            return new ExecutionItem(
                    execution.getId(),
                    execution.getIniciadaEn(),
                    execution.getFinalizadaEn(),
                    execution.getResultado(),
                    publicMetadata(execution.getMetadata()),
                    execution.getMotivoError(), execution.getSequenceNumber(), size, file != null,
                    execution.getOrigin(), execution.getPhase(), null);
        }

        private static Map<String, Object> publicMetadata(Map<String, Object> values) {
            var result = new java.util.LinkedHashMap<>(values);
            result.remove("cloudRecoveryKey");
            result.remove("cloudDescriptor");
            return java.util.Collections.unmodifiableMap(result);
        }

        private static Path availablePath(Map<String, Object> metadata) {
            for (String key : List.of("path", "monthlyPath")) {
                if (metadata.get(key) instanceof String text) {
                    try {
                        Path path = Path.of(text);
                        if (Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return path;
                    } catch (java.nio.file.InvalidPathException ignored) { }
                }
            }
            return null;
        }
    }
}

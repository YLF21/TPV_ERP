package com.tpverp.backend.backup;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.verifactu.FiscalEventService;
import com.tpverp.backend.verifactu.FiscalEventType;
import com.tpverp.backend.verifactu.FiscalMode;
import com.tpverp.backend.verifactu.VerifactuConfigurationRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomic DB half of restore finalize: fiscal events/audit and idempotency marker. */
@Service
public class BackupRestoreFinalizationCommitService {
    private final BackupRestoreFinalizationRepository finalizations;
    private final InstallationRepository installations;
    private final VerifactuConfigurationRepository fiscalConfigurations;
    private final FiscalEventService fiscalEvents;
    private final AuditService audit;

    public BackupRestoreFinalizationCommitService(BackupRestoreFinalizationRepository finalizations,
            InstallationRepository installations, VerifactuConfigurationRepository fiscalConfigurations,
            FiscalEventService fiscalEvents, AuditService audit) {
        this.finalizations = finalizations;
        this.installations = installations;
        this.fiscalConfigurations = fiscalConfigurations;
        this.fiscalEvents = fiscalEvents;
        this.audit = audit;
    }

    @Transactional
    public void commit(UUID journalId, String backupSha256) {
        var existing = finalizations.findById(journalId);
        if (existing.isPresent()) {
            if (!existing.get().getBackupSha256().equalsIgnoreCase(backupSha256)) {
                throw new IllegalStateException("El journalId ya fue finalizado con otra huella");
            }
            return;
        }
        var installationRows = installations.findAll();
        if (installationRows.size() != 1) {
            throw new IllegalStateException("La instalación restaurada debe ser singleton; no se puede elegir silenciosamente");
        }
        var installation = installationRows.getFirst();
        var configurations = fiscalConfigurations.findAll();
        if (configurations.isEmpty()) {
            throw new IllegalStateException("No se puede determinar de forma segura el modo fiscal restaurado");
        }
        for (var configuration : configurations) {
            if (configuration.getCurrentMode() == FiscalMode.NO_VERIFACTU) {
                fiscalEvents.create(configuration.getCompanyId(), installation.getId(),
                        FiscalMode.NO_VERIFACTU, FiscalEventType.BACKUP_RESTORED,
                        "Restauración offline " + journalId);
            } else if (configuration.getCurrentMode() == FiscalMode.VERIFACTU) {
                audit.record("BACKUP_RESTORED", AuditResult.EXITO,
                        Map.of("journalId", journalId.toString(), "fiscalMode", "VERIFACTU"));
            } else if (configuration.getCurrentMode() == FiscalMode.PRE_SIF) {
                audit.record("BACKUP_RESTORED", AuditResult.EXITO,
                        Map.of("journalId", journalId.toString(), "fiscalMode", "PRE_SIF"));
            } else {
                throw new IllegalStateException("Modo fiscal restaurado no soportado");
            }
        }
        String modeSummary = configurations.stream().map(configuration -> configuration.getCurrentMode().name())
                .distinct().count() == 1
                        ? configurations.getFirst().getCurrentMode().name() : "MIXED";
        finalizations.saveAndFlush(new BackupRestoreFinalization(
                journalId, backupSha256.toUpperCase(java.util.Locale.ROOT),
                modeSummary, Instant.now()));
    }

    @Transactional(readOnly = true)
    public void verifyMarker(UUID journalId, String backupSha256) {
        var marker = finalizations.findById(journalId)
                .orElseThrow(() -> new IllegalStateException("Journal FINALIZED sin marker DB"));
        if (!marker.getBackupSha256().equalsIgnoreCase(backupSha256)) {
            throw new IllegalStateException("Marker DB no coincide con la huella del journal");
        }
    }
}

package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalExportService {
    private final CurrentOrganization organization;
    private final InstallationRepository installations;
    private final VerifactuConfigurationRepository configurations;
    private final FiscalRecordRepository records;
    private final FiscalRecordArtifactRepository artifacts;
    private final FiscalEventRepository events;
    private final FiscalExportRepository exports;
    private final FiscalEventService eventService;

    public FiscalExportService(CurrentOrganization organization, InstallationRepository installations,
            VerifactuConfigurationRepository configurations, FiscalRecordRepository records,
            FiscalRecordArtifactRepository artifacts, FiscalEventRepository events,
            FiscalEventService eventService,
            FiscalExportRepository exports) {
        this.organization = organization;
        this.installations = installations;
        this.configurations = configurations;
        this.records = records;
        this.artifacts = artifacts;
        this.events = events;
        this.exports = exports;
        this.eventService = eventService;
    }

    @Transactional
    public FiscalExportView export(FiscalExportKind kind) {
        var company = organization.currentCompany();
        var installation = installations.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Instalacion fiscal no encontrada"));
        var mode = configurations.findByCompanyId(company.getId())
                .map(VerifactuConfiguration::getCurrentMode).orElse(FiscalMode.PRE_SIF);
        var now = Instant.now();
        UUID eventId = null;
        List<String> xml;
        if (kind == FiscalExportKind.BILLING) {
            var fiscalRecords = records.findAllByCompanyIdAndInstallationIdOrderBySequence(
                    company.getId(), installation.getId());
            xml = fiscalRecords.stream().map(record -> artifacts.findByRecordId(record.getId())
                    .map(artifact -> artifact.getSignedXml() == null
                            ? artifact.getUnsignedXml() : artifact.getSignedXml())
                    .orElseThrow(() -> new IllegalStateException(
                            "Registro fiscal sin artefacto congelado: " + record.getId())))
                    .toList();
            if (mode == FiscalMode.NO_VERIFACTU) {
                var event = eventService.create(company.getId(), installation.getId(), mode,
                        FiscalEventType.BILLING_EXPORT, null);
                eventId = event.getId();
            }
        } else {
            var fiscalEvents = events.findAllByCompanyIdAndInstallationIdOrderBySequenceAsc(
                    company.getId(), installation.getId());
            xml = fiscalEvents.stream().map(FiscalEvent::getSignedXml).toList();
            if (mode == FiscalMode.NO_VERIFACTU) {
                var event = eventService.create(company.getId(), installation.getId(), mode,
                        FiscalEventType.EVENT_EXPORT, null);
                eventId = event.getId();
            }
        }
        var persisted = exports.save(new FiscalExport(company.getId(), installation.getId(), kind,
                eventId, xml.size(), sha256(xml), now));
        return new FiscalExportView(persisted.getId(), kind, now, xml.size(), eventId, xml);
    }

    private static String sha256(List<String> values) {
        try {
            var bytes = String.join("\n", values).getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }
}

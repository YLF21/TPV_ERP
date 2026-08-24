package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalIntegrityService {
    private final CurrentOrganization organization;
    private final InstallationRepository installations;
    private final VerifactuConfigurationRepository configurations;
    private final FiscalRecordRepository records;
    private final FiscalEventRepository events;
    private final FiscalAlarmRepository alarms;
    private final FiscalEventService eventService;
    private final FiscalJsonHasher jsonHasher = new FiscalJsonHasher();
    private FiscalRecordArtifactRepository artifacts;

    public FiscalIntegrityService(CurrentOrganization organization,
            InstallationRepository installations, VerifactuConfigurationRepository configurations,
            FiscalRecordRepository records, FiscalEventRepository events,
            FiscalAlarmRepository alarms, FiscalEventService eventService) {
        this.organization = organization;
        this.installations = installations;
        this.configurations = configurations;
        this.records = records;
        this.events = events;
        this.alarms = alarms;
        this.eventService = eventService;
    }

    @Autowired(required = false)
    void setArtifacts(FiscalRecordArtifactRepository artifacts) {
        this.artifacts = artifacts;
    }

    @Transactional
    public FiscalIntegrityCheckView check() {
        var company = organization.currentCompany();
        var installation = installations.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Instalacion fiscal no encontrada"));
        var mode = configurations.findByCompanyId(company.getId())
                .map(VerifactuConfiguration::getCurrentMode).orElse(FiscalMode.PRE_SIF);
        var billing = records.findAllByCompanyIdAndInstallationIdOrderBySequence(
                company.getId(), installation.getId());
        var eventRecords = events.findAllByCompanyIdAndInstallationIdOrderBySequenceAsc(
                company.getId(), installation.getId());
        var anomalies = new ArrayList<String>();
        for (var record : billing) {
            if (!Objects.equals(record.getSnapshotHash(), jsonHasher.hash(record.getSnapshot()))) {
                anomalies.add("INTEGRIDAD_SNAPSHOT_" + record.getSequence());
            }
            if (artifacts != null) {
                artifacts.findByRecordId(record.getId()).ifPresent(artifact -> {
                    var xml = artifact.getSignedXml() == null
                            ? artifact.getUnsignedXml() : artifact.getSignedXml();
                    if (!Objects.equals(artifact.getXmlHash(), sha256(xml))) {
                        anomalies.add("INTEGRIDAD_ARTEFACTO_XML_" + record.getSequence());
                    }
                });
            }
        }
        for (var event : eventRecords) {
            if (!Objects.equals(event.getXmlHash(), sha256(event.getSignedXml()))) {
                anomalies.add("INTEGRIDAD_XML_EVENTO_" + event.getSequence());
            }
        }
        for (var index = 1; index < billing.size(); index++) {
            if (!java.util.Objects.equals(billing.get(index).getPreviousHash(),
                    billing.get(index - 1).getHash())) {
                anomalies.add("CADENA_FACTURACION_" + billing.get(index).getSequence());
            }
        }
        for (var index = 1; index < eventRecords.size(); index++) {
            if (!java.util.Objects.equals(eventRecords.get(index).getPreviousHash(),
                    eventRecords.get(index - 1).getHash())) {
                anomalies.add("CADENA_EVENTOS_" + eventRecords.get(index).getSequence());
            }
        }
        if (mode == FiscalMode.NO_VERIFACTU) {
            var checkedAt = Instant.now();
            anomalies.forEach(anomaly -> alarms.save(new FiscalAlarm(company.getId(),
                    installation.getId(), anomaly, "Anomalía de integridad detectada", checkedAt)));
            eventService.create(company.getId(), installation.getId(), mode,
                    FiscalEventType.BILLING_ANOMALY_SCAN_STARTED, null);
            eventService.create(company.getId(), installation.getId(), mode,
                    FiscalEventType.EVENT_ANOMALY_SCAN_STARTED, null);
            if (anomalies.stream().anyMatch(FiscalIntegrityService::isBillingAnomaly)) {
                eventService.create(company.getId(), installation.getId(), mode,
                        FiscalEventType.BILLING_ANOMALY_DETECTED, String.join(",", anomalies));
            }
            if (anomalies.stream().anyMatch(FiscalIntegrityService::isEventAnomaly)) {
                eventService.create(company.getId(), installation.getId(), mode,
                        FiscalEventType.EVENT_ANOMALY_DETECTED, String.join(",", anomalies));
            }
        }
        return new FiscalIntegrityCheckView(Instant.now(), mode, anomalies.isEmpty(),
                List.copyOf(anomalies), billing.size(), eventRecords.size());
    }

    private static boolean isBillingAnomaly(String value) {
        return value.startsWith("CADENA_FACTURACION")
                || value.startsWith("INTEGRIDAD_SNAPSHOT")
                || value.startsWith("INTEGRIDAD_ARTEFACTO_XML");
    }

    private static boolean isEventAnomaly(String value) {
        return value.startsWith("CADENA_EVENTOS") || value.startsWith("INTEGRIDAD_XML_EVENTO");
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }
}

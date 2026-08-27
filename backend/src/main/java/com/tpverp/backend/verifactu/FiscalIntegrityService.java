package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.CompanyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalIntegrityService {
    private static final int BATCH_SIZE = 500;
    private static final long SYNCHRONOUS_MAX_RECORDS = 1_000L;
    private final CurrentOrganization organization;
    private final CompanyRepository companies;
    private final InstallationRepository installations;
    private final LicenseRepository licenses;
    private final VerifactuConfigurationRepository configurations;
    private final FiscalRecordRepository records;
    private final FiscalEventRepository events;
    private final FiscalAlarmRepository alarms;
    private final FiscalEventService eventService;
    private final FiscalQrUrlService qrUrls;
    private final FiscalIntegrityScanEventRecorder scanEvents;
    private boolean legacyFixtureMode;
    private final OfficialHashService officialHashes = new OfficialHashService();
    private final FiscalJsonHasher jsonHasher = new FiscalJsonHasher();
    private FiscalRecordArtifactRepository artifacts;
    private FiscalPrintSnapshotRecordRepository printSnapshots;
    private FiscalXadesSigner signer;
    private FiscalSystemVersionRepository systemVersions;

    @Autowired
    public FiscalIntegrityService(CurrentOrganization organization,
            CompanyRepository companies,
            InstallationRepository installations, LicenseRepository licenses,
            VerifactuConfigurationRepository configurations,
            FiscalRecordRepository records, FiscalEventRepository events,
            FiscalAlarmRepository alarms, FiscalEventService eventService,
            FiscalQrUrlService qrUrls,
            FiscalRecordArtifactRepository artifacts,
            FiscalPrintSnapshotRecordRepository printSnapshots,
            FiscalXadesSigner signer,
            FiscalSystemVersionRepository systemVersions,
            FiscalIntegrityScanEventRecorder scanEvents) {
        this.organization = organization;
        this.companies = companies;
        this.installations = installations;
        this.licenses = licenses;
        this.configurations = configurations;
        this.records = records;
        this.events = events;
        this.alarms = alarms;
        this.eventService = eventService;
        this.qrUrls = qrUrls;
        // These are mandatory constructor dependencies in production wiring;
        // nulls are accepted only by the deprecated fixture constructors below.
        this.artifacts = artifacts;
        this.printSnapshots = printSnapshots;
        this.signer = signer;
        this.systemVersions = systemVersions;
        this.scanEvents = scanEvents;
    }

    @Deprecated(forRemoval = true)
    public FiscalIntegrityService(CurrentOrganization organization,
            InstallationRepository installations, LicenseRepository licenses,
            VerifactuConfigurationRepository configurations,
            FiscalRecordRepository records, FiscalEventRepository events,
            FiscalAlarmRepository alarms, FiscalEventService eventService,
            FiscalQrUrlService qrUrls,
            FiscalRecordArtifactRepository artifacts,
            FiscalPrintSnapshotRecordRepository printSnapshots,
            FiscalXadesSigner signer,
            FiscalSystemVersionRepository systemVersions,
            FiscalIntegrityScanEventRecorder scanEvents) {
        this(organization, null, installations, licenses, configurations, records, events,
                alarms, eventService, qrUrls, artifacts, printSnapshots, signer,
                systemVersions, scanEvents);
    }

    @Deprecated(forRemoval = true)
    public FiscalIntegrityService(CurrentOrganization organization,
            InstallationRepository installations, LicenseRepository licenses,
            VerifactuConfigurationRepository configurations,
            FiscalRecordRepository records, FiscalEventRepository events,
            FiscalAlarmRepository alarms, FiscalEventService eventService,
            FiscalQrUrlService qrUrls) {
        this(organization, null, installations, licenses, configurations, records, events,
                alarms, eventService, qrUrls, null, null, null, null, null);
    }

    /** Compatibility constructor for focused unit tests and adapters. */
    @Deprecated(forRemoval = true)
    public FiscalIntegrityService(CurrentOrganization organization,
            InstallationRepository installations, VerifactuConfigurationRepository configurations,
            FiscalRecordRepository records, FiscalEventRepository events,
            FiscalAlarmRepository alarms, FiscalEventService eventService,
            FiscalQrUrlService qrUrls) {
        this(organization, null, installations, null, configurations, records, events, alarms,
                eventService, qrUrls, null, null, null, null, null);
        this.legacyFixtureMode = true;
    }

    /** Test-fixture injection retained only for the deprecated constructor. */
    @Deprecated(forRemoval = true)
    void setArtifacts(FiscalRecordArtifactRepository artifacts) {
        if (legacyFixtureMode) {
            this.artifacts = artifacts;
        }
    }

    @Deprecated(forRemoval = true)
    void setPrintSnapshots(FiscalPrintSnapshotRecordRepository printSnapshots) {
        if (legacyFixtureMode) {
            this.printSnapshots = printSnapshots;
        }
    }

    @Deprecated(forRemoval = true)
    void setSigner(FiscalXadesSigner signer) {
        if (legacyFixtureMode) {
            this.signer = signer;
        }
    }

    @Deprecated(forRemoval = true)
    void setSystemVersions(FiscalSystemVersionRepository systemVersions) {
        if (legacyFixtureMode) {
            this.systemVersions = systemVersions;
        }
    }

    /**
     * Synchronous by contract, but bounded by a database transaction timeout so
     * an unexpectedly large installation fails explicitly instead of keeping an
     * HTTP request open indefinitely. A timeout never produces an "integral"
     * result because the transaction is rolled back and no view is returned.
     */
    @Transactional(timeoutString = "${tpv.verifactu.integrity-check-timeout-seconds:300}")
    public FiscalIntegrityCheckView check() {
        return legacyFixtureMode ? checkLegacy() : checkBatched();
    }

    /** Legacy HTTP adapter guard; large chains must use the durable job API. */
    public void rejectSynchronousIfTooLarge() {
        if (legacyFixtureMode) return;
        var company = organization.currentCompany();
        var installation = FiscalInstallationResolver.resolveCurrent(organization, installations, licenses);
        if (records.existsByCompanyIdAndInstallationIdAndSequenceGreaterThan(
                company.getId(), installation.getId(), SYNCHRONOUS_MAX_RECORDS)
                || events.existsByCompanyIdAndInstallationIdAndSequenceGreaterThan(
                        company.getId(), installation.getId(), SYNCHRONOUS_MAX_RECORDS)) {
            throw new IllegalStateException("fiscal_integrity_use_jobs");
        }
    }

    /** Context-free entry point for the durable integrity worker. */
    public FiscalIntegrityCheckView checkSnapshot(java.util.UUID companyId,
            java.util.UUID installationId, FiscalMode mode,
            long maximumBillingSequence, long maximumEventSequence,
            IntegrityProgressListener progress) {
        if (companies == null || systemVersions == null || artifacts == null
                || printSnapshots == null || signer == null) {
            throw new IllegalStateException("El control de integridad no esta completamente configurado");
        }
        var company = companies.findById(companyId)
                .orElseThrow(() -> new IllegalStateException("Empresa fiscal no encontrada"));
        var installation = installations.findById(installationId)
                .orElseThrow(() -> new IllegalStateException("Instalacion fiscal no encontrada"));
        ensureModeUnchanged(companyId, mode);
        if (maximumBillingSequence < 0 || maximumEventSequence < 0) {
            throw new IllegalArgumentException("El corte de integridad no puede ser negativo");
        }
        var listener = progress == null ? IntegrityProgressListener.NONE : progress;
        var anomalies = new AnomalyCollector();
        listener.onProgress(0, 0, 0, 0, 0, anomalies);
        if (mode == FiscalMode.NO_VERIFACTU && scanEvents != null) {
            scanEvents.recordStarted(companyId, installationId, mode);
        }
        long billingCount = checkBillingBatches(companyId, installationId,
                maximumBillingSequence, anomalies, listener);
        long eventCount = checkEventBatches(companyId, installationId,
                maximumEventSequence, company.getTaxId(), installation.getReferencia(),
                anomalies, listener, billingCount);
        ensureModeUnchanged(companyId, mode);
        listener.onProgress(billingCount, eventCount, anomalies.total(),
                anomalies.categoryTotal("billing"), anomalies.categoryTotal("events"), anomalies);
        persistAnomalyEvents(companyId, installationId, mode, anomalies);
        return new FiscalIntegrityCheckView(Instant.now(), mode, anomalies.isEmpty(),
                List.copyOf(anomalies), billingCount, eventCount, anomalies.total(),
                anomalies.categoryTotal("billing"), anomalies.categoryTotal("events"));
    }

    /**
     * Compatibility path retained only for the deprecated constructor used by
     * pre-batching unit fixtures; it is not reachable from Spring production
     * wiring. New tests must provide the licensed constructor.
     */
    private FiscalIntegrityCheckView checkLegacy() {
        var company = organization.currentCompany();
        var installation = licenses == null
                ? FiscalInstallationResolver.resolveForCompany(company.getId(), installations, null)
                : FiscalInstallationResolver.resolveCurrent(organization, installations, licenses);
        var mode = configurations.findByCompanyId(company.getId())
                .map(VerifactuConfiguration::getCurrentMode).orElse(FiscalMode.PRE_SIF);
        if (mode == FiscalMode.NO_VERIFACTU) {
            // This path is retained only for deprecated unit fixtures. Keep
            // its observable ordering aligned with production: launch evidence
            // is emitted before loading the chains, never after the scan.
            if (scanEvents != null) {
                scanEvents.recordStarted(company.getId(), installation.getId(), mode);
            } else {
                eventService.create(company.getId(), installation.getId(), mode,
                        FiscalEventType.BILLING_ANOMALY_SCAN_STARTED, null);
                eventService.create(company.getId(), installation.getId(), mode,
                        FiscalEventType.EVENT_ANOMALY_SCAN_STARTED, null);
            }
        }
        var billing = records.findAllByCompanyIdAndInstallationIdOrderBySequence(
                company.getId(), installation.getId());
        var eventRecords = events.findAllByCompanyIdAndInstallationIdOrderBySequenceAsc(
                company.getId(), installation.getId());
        var anomalies = new ArrayList<String>();
        for (var record : billing) {
            if (!Objects.equals(record.getSnapshotHash(), jsonHasher.hash(record.getSnapshot()))) {
                anomalies.add("INTEGRIDAD_SNAPSHOT_" + record.getSequence());
            }
            FiscalRecordArtifact artifact = null;
            if (artifacts != null) {
                artifact = artifacts.findByRecordId(record.getId()).orElse(null);
                if (artifact == null && record.getFiscalMode() != FiscalMode.PRE_SIF) {
                    anomalies.add("INTEGRIDAD_ARTEFACTO_AUSENTE_" + record.getSequence());
                }
                if (artifact != null) {
                    var xml = artifact.getSignedXml() == null
                            ? artifact.getUnsignedXml() : artifact.getSignedXml();
                    if (!Objects.equals(artifact.getXmlHash(), sha256(xml))) {
                        anomalies.add("INTEGRIDAD_ARTEFACTO_XML_" + record.getSequence());
                    }
                    if (record.getFiscalMode() == FiscalMode.NO_VERIFACTU
                            && (artifact.getSignedXml() == null
                                    || signer == null
                                    || !signer.verifySignedXml(artifact.getSignedXml(),
                                            artifact.getCertificateFingerprint()))) {
                        anomalies.add("FIRMA_REGISTRO_" + record.getSequence());
                    }
                }
            }
            if (printSnapshots != null && record.getFiscalMode() != FiscalMode.PRE_SIF) {
                var printSnapshot = printSnapshots.findByRecordId(record.getId()).orElse(null);
                validatePrintEvidence(record, artifact, printSnapshot, anomalies);
            }
        }
        for (var event : eventRecords) {
            if (!Objects.equals(event.getXmlHash(), sha256(event.getSignedXml()))) {
                anomalies.add("INTEGRIDAD_XML_EVENTO_" + event.getSequence());
            }
            if (signer != null && !signer.verifySignedXml(event.getSignedXml())) {
                anomalies.add("FIRMA_EVENTO_" + event.getSequence());
            }
        }
        ensureModeUnchanged(company.getId(), mode);
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

    /**
     * Checks the complete fiscal chains with immutable-sequence keyset reads.
     * Each batch is bounded, XML/JSON entities are released between batches,
     * and both chain order and exact checked counters are preserved.
     */
    private FiscalIntegrityCheckView checkBatched() {
        var company = organization.currentCompany();
        var installation = FiscalInstallationResolver.resolveCurrent(
                organization, installations, licenses);
        var mode = configurations.findByCompanyId(company.getId())
                .map(VerifactuConfiguration::getCurrentMode).orElse(FiscalMode.PRE_SIF);
        var anomalies = new AnomalyCollector();
        var latestBilling = records
                .findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                        company.getId(), installation.getId()).orElse(null);
        var maximumBillingSequence = latestBilling == null
                ? 0L : latestBilling.getSequence();
        var maximumEventSequence = events
                .findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                        company.getId(), installation.getId())
                .map(FiscalEvent::getSequence).orElse(0L);
        if (scanEvents != null && mode == FiscalMode.NO_VERIFACTU) {
            // Freeze the scan boundary before recording these launch events so
            // the events do not become part of their own integrity input.
            scanEvents.recordStarted(company.getId(), installation.getId(), mode);
        }
        long billingCount = checkBillingBatches(
                company.getId(), installation.getId(), maximumBillingSequence, anomalies,
                IntegrityProgressListener.NONE);
        long eventCount = checkEventBatches(
                company.getId(), installation.getId(), maximumEventSequence,
                company.getTaxId(), installation.getReferencia(),
                anomalies, IntegrityProgressListener.NONE, billingCount);
        ensureModeUnchanged(company.getId(), mode);
        persistAnomalyEvents(company.getId(), installation.getId(), mode, anomalies);
        return new FiscalIntegrityCheckView(Instant.now(), mode, anomalies.isEmpty(),
                List.copyOf(anomalies), billingCount, eventCount, anomalies.total(),
                anomalies.categoryTotal("billing"), anomalies.categoryTotal("events"));
    }

    private long checkBillingBatches(
            java.util.UUID companyId,
            java.util.UUID installationId,
            long maximumSequence,
            List<String> anomalies,
            IntegrityProgressListener progress) {
        long checked = 0;
        long lastSequence = 0;
        String previousHash = null;
        while (true) {
            ensureWorkerNotInterrupted();
            var batch = records.findIntegrityBatch(companyId, installationId, lastSequence,
                    maximumSequence, PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            var ids = batch.stream().map(FiscalRecord::getId).toList();
            Map<java.util.UUID, FiscalRecordArtifact> artifactById = artifacts == null
                    ? Map.of()
                    : indexArtifacts(artifacts.findAllByRecordIdIn(ids));
            Map<java.util.UUID, FiscalPrintSnapshotRecord> printSnapshotById = printSnapshots == null
                    ? Map.of()
                    : indexPrintSnapshots(printSnapshots.findAllByRecordIdIn(ids));
            for (var record : batch) {
                ensureWorkerNotInterrupted();
                if (checked == 0 && record.getSequence() != 1) {
                    anomalies.add("CADENA_FACTURACION_" + record.getSequence());
                }
                if (checked > 0 && record.getSequence() != lastSequence + 1) {
                    anomalies.add("CADENA_FACTURACION_" + record.getSequence());
                }
                if (checked > 0 && !Objects.equals(record.getPreviousHash(), previousHash)) {
                    anomalies.add("CADENA_FACTURACION_" + record.getSequence());
                }
                if (!Objects.equals(record.getSnapshotHash(), jsonHasher.hash(record.getSnapshot()))) {
                    anomalies.add("INTEGRIDAD_SNAPSHOT_" + record.getSequence());
                }
                verifyOfficialRecordHash(record, anomalies);
                var artifact = artifactById.get(record.getId());
                if (artifacts != null) {
                    if (artifact == null && record.getFiscalMode() != FiscalMode.PRE_SIF) {
                        anomalies.add("INTEGRIDAD_ARTEFACTO_AUSENTE_" + record.getSequence());
                    }
                    if (artifact != null) {
                        var xml = artifact.getSignedXml() == null
                                ? artifact.getUnsignedXml() : artifact.getSignedXml();
                        if (!Objects.equals(artifact.getXmlHash(), sha256(xml))) {
                            anomalies.add("INTEGRIDAD_ARTEFACTO_XML_" + record.getSequence());
                        }
                        if (record.getFiscalMode() == FiscalMode.NO_VERIFACTU
                                && (artifact.getSignedXml() == null
                                || signer == null
                                || !signer.verifySignedXml(artifact.getSignedXml(),
                                        artifact.getCertificateFingerprint()))) {
                            anomalies.add("FIRMA_REGISTRO_" + record.getSequence());
                        }
                    }
                }
                if (printSnapshots != null && record.getFiscalMode() != FiscalMode.PRE_SIF) {
                    validatePrintEvidence(record, artifact,
                            printSnapshotById.get(record.getId()), anomalies);
                }
                checked++;
                previousHash = record.getHash();
                lastSequence = record.getSequence();
            }
            progress.onProgress(checked, 0, anomalyTotal(anomalies),
                    billingAnomalyTotal(anomalies), eventAnomalyTotal(anomalies), anomalies);
        }
        return checked;
    }

    private long checkEventBatches(
            java.util.UUID companyId,
            java.util.UUID installationId,
            long maximumSequence,
            String obligatedTaxId,
            String installationNumber,
            List<String> anomalies,
            IntegrityProgressListener progress,
            long billingChecked) {
        long checked = 0;
        long lastSequence = 0;
        String previousHash = null;
        while (true) {
            ensureWorkerNotInterrupted();
            var batch = events.findIntegrityBatch(companyId, installationId, lastSequence,
                    maximumSequence, PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            Map<java.util.UUID, FiscalSystemVersion> systemById = systemVersions == null
                    ? Map.of()
                    : indexSystemVersions(batch.stream()
                            .map(FiscalEvent::getSystemVersionId)
                            .filter(Objects::nonNull).distinct().toList());
            for (var event : batch) {
                ensureWorkerNotInterrupted();
                if (checked == 0 && event.getSequence() != 1) {
                    anomalies.add("CADENA_EVENTOS_" + event.getSequence());
                }
                if (checked > 0 && event.getSequence() != lastSequence + 1) {
                    anomalies.add("CADENA_EVENTOS_" + event.getSequence());
                }
                if (checked > 0 && !Objects.equals(event.getPreviousHash(), previousHash)) {
                    anomalies.add("CADENA_EVENTOS_" + event.getSequence());
                }
                if (!Objects.equals(event.getXmlHash(), sha256(event.getSignedXml()))) {
                    anomalies.add("INTEGRIDAD_XML_EVENTO_" + event.getSequence());
                }
                if (signer != null && !signer.verifySignedXml(event.getSignedXml())) {
                    anomalies.add("FIRMA_EVENTO_" + event.getSequence());
                }
                verifyOfficialEventHash(event, systemById, obligatedTaxId, installationNumber,
                        anomalies);
                checked++;
                previousHash = event.getHash();
                lastSequence = event.getSequence();
            }
            progress.onProgress(billingChecked, checked, anomalyTotal(anomalies),
                    billingAnomalyTotal(anomalies), eventAnomalyTotal(anomalies), anomalies);
        }
        return checked;
    }

    private void persistAnomalyEvents(
            java.util.UUID companyId,
            java.util.UUID installationId,
            FiscalMode mode,
            AnomalyCollector anomalies) {
        if (scanEvents != null) {
            scanEvents.recordResult(companyId, installationId, mode,
                    anomalies.total() == 0 ? null : compactAnomalyDetail("anomalies", anomalies),
                    anomalies.hasBillingAnomaly() ? compactAnomalyDetail("billing", anomalies) : null,
                    anomalies.hasEventAnomaly() ? compactAnomalyDetail("events", anomalies) : null);
            return;
        }
        if (mode != FiscalMode.NO_VERIFACTU || anomalies.total() == 0) return;
        var checkedAt = Instant.now();
        // Keep the durable alarm bounded as well as the response. The complete
        // count and first evidence codes remain available in the result and in
        // this deterministic, short detail; one aggregate blocking alarm avoids
        // turning a large corruption into thousands of inserts.
        alarms.save(new FiscalAlarm(companyId, installationId,
                "INTEGRIDAD_ANOMALIAS",
                compactAnomalyDetail("anomalies", anomalies), checkedAt));
        if (anomalies.hasBillingAnomaly()) {
            eventService.create(companyId, installationId, mode,
                    FiscalEventType.BILLING_ANOMALY_DETECTED,
                    compactAnomalyDetail("billing", anomalies));
        }
        if (anomalies.hasEventAnomaly()) {
            eventService.create(companyId, installationId, mode,
                    FiscalEventType.EVENT_ANOMALY_DETECTED,
                    compactAnomalyDetail("events", anomalies));
        }
    }

    private static String compactAnomalyDetail(String category, AnomalyCollector anomalies) {
        var detail = new StringBuilder(category).append(" total=")
                .append(anomalies.categoryTotal(category)).append(";");
        for (var anomaly : anomalies) {
            if (!"anomalies".equals(category)
                    && !("billing".equals(category) && isBillingAnomaly(anomaly))
                    && !("events".equals(category) && isEventAnomaly(anomaly))) {
                continue;
            }
            var candidate = detail + anomaly + "|";
            if (candidate.length() > 100) {
                break;
            }
            detail.append(anomaly).append('|');
        }
        return detail.toString();
    }

    private void verifyOfficialRecordHash(FiscalRecord record, List<String> anomalies) {
        try {
            var generatedAt = record.getGeneratedAt()
                    .atZone(ZoneId.of(record.getTimezone())).toOffsetDateTime();
            var expected = record.getOperation() == FiscalRecordOperation.ALTA
                    ? officialHashes.hash(new AltaHashInput(
                            record.getIssuerTaxId(), record.getNumber(),
                            record.getIssueDate().toString(), record.getDocumentType().name(),
                            record.getTotalTax(), record.getTotalAmount(),
                            record.getPreviousHash(), generatedAt))
                    : officialHashes.hash(new CancellationHashInput(
                            record.getIssuerTaxId(), record.getNumber(),
                            record.getIssueDate().toString(), record.getPreviousHash(),
                            generatedAt));
            if (!Objects.equals(record.getHash(), expected)) {
                anomalies.add("INTEGRIDAD_HUELLA_" + record.getSequence());
            }
        } catch (RuntimeException exception) {
            anomalies.add("INTEGRIDAD_HUELLA_" + record.getSequence());
        }
    }

    private void verifyOfficialEventHash(
            FiscalEvent event,
            Map<java.util.UUID, FiscalSystemVersion> systemById,
            String obligatedTaxId,
            String installationNumber,
            List<String> anomalies) {
        if (systemVersions == null || event.getSystemVersionId() == null) {
            anomalies.add("INTEGRIDAD_HUELLA_EVENTO_" + event.getSequence());
            return;
        }
        var system = systemById.get(event.getSystemVersionId());
        if (system == null) {
            anomalies.add("INTEGRIDAD_HUELLA_EVENTO_" + event.getSequence());
            return;
        }
        try {
            var generatedAt = persistedEventTimestamp(event);
            if (generatedAt == null || event.getGeneratedAt() == null
                    || !generatedAt.toInstant().equals(
                            event.getGeneratedAt().truncatedTo(ChronoUnit.SECONDS))) {
                anomalies.add("INTEGRIDAD_HUELLA_EVENTO_" + event.getSequence());
                return;
            }
            var expected = officialHashes.hash(new FiscalEventHashInput(
                    system.getProducerTaxId(), "", system.getSystemId(),
                    system.getSystemVersion(), installationNumber, obligatedTaxId,
                    event.getType().code(), event.getPreviousHash(),
                    generatedAt));
            if (!Objects.equals(event.getHash(), expected)) {
                anomalies.add("INTEGRIDAD_HUELLA_EVENTO_" + event.getSequence());
            }
        } catch (RuntimeException exception) {
            anomalies.add("INTEGRIDAD_HUELLA_EVENTO_" + event.getSequence());
        }
    }

    private static OffsetDateTime persistedEventTimestamp(FiscalEvent event) {
        try {
            return FiscalFrozenTimestampReader.read(event.getSignedXml());
        } catch (Exception exception) {
            return null;
        }
    }

    static final class AnomalyCollector extends ArrayList<String> {
        private static final int MAX_EVIDENCE = 1_000;
        private final Set<String> retainedEvidenceCodes = new HashSet<>(MAX_EVIDENCE);
        private long total;
        private long billingTotal;
        private long eventTotal;

        @Override
        public boolean add(String anomaly) {
            total++;
            if (isBillingAnomaly(anomaly)) {
                billingTotal++;
            }
            if (isEventAnomaly(anomaly)) {
                eventTotal++;
            }
            if (size() >= MAX_EVIDENCE) {
                return true;
            }
            var added = super.add(anomaly);
            retainedEvidenceCodes.add(anomaly);
            return added;
        }

        boolean addUnique(String anomaly) {
            if (retainedEvidenceCodes.contains(anomaly)) {
                return false;
            }
            return add(anomaly);
        }

        long total() {
            return total;
        }

        boolean hasBillingAnomaly() {
            return billingTotal > 0;
        }

        boolean hasEventAnomaly() {
            return eventTotal > 0;
        }

        long categoryTotal(String category) {
            return switch (category) {
                case "billing" -> billingTotal;
                case "events" -> eventTotal;
                default -> total;
            };
        }
    }

    private static Map<java.util.UUID, FiscalRecordArtifact> indexArtifacts(
            List<FiscalRecordArtifact> values) {
        var indexed = new HashMap<java.util.UUID, FiscalRecordArtifact>(values.size());
        values.forEach(value -> indexed.put(value.getRecordId(), value));
        return indexed;
    }

    private static Map<java.util.UUID, FiscalPrintSnapshotRecord> indexPrintSnapshots(
            List<FiscalPrintSnapshotRecord> values) {
        var indexed = new HashMap<java.util.UUID, FiscalPrintSnapshotRecord>(values.size());
        values.forEach(value -> indexed.put(value.getRecordId(), value));
        return indexed;
    }

    private Map<java.util.UUID, FiscalSystemVersion> indexSystemVersions(
            List<java.util.UUID> ids) {
        var indexed = new HashMap<java.util.UUID, FiscalSystemVersion>(ids.size());
        systemVersions.findAllById(ids).forEach(value -> indexed.put(value.getId(), value));
        return indexed;
    }

    private static boolean isBillingAnomaly(String value) {
        return value.startsWith("CADENA_FACTURACION")
                || value.startsWith("INTEGRIDAD_SNAPSHOT")
                || value.startsWith("INTEGRIDAD_ARTEFACTO_XML")
                || value.startsWith("INTEGRIDAD_ARTEFACTO_AUSENTE")
                || (value.startsWith("INTEGRIDAD_HUELLA_")
                        && !value.startsWith("INTEGRIDAD_HUELLA_EVENTO"))
                || value.startsWith("INTEGRIDAD_ANOMALIAS_SUPRIMIDAS")
                || value.startsWith("INTEGRIDAD_SNAPSHOT_IMPRESION_AUSENTE")
                || value.startsWith("INTEGRIDAD_QR_ANULACION")
                || value.startsWith("FIRMA_REGISTRO");
    }

    private static boolean isEventAnomaly(String value) {
        return value.startsWith("CADENA_EVENTOS") || value.startsWith("INTEGRIDAD_XML_EVENTO")
                || value.startsWith("INTEGRIDAD_HUELLA_EVENTO")
                || value.startsWith("FIRMA_EVENTO");
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

    private void validatePrintEvidence(
            FiscalRecord record,
            FiscalRecordArtifact artifact,
            FiscalPrintSnapshotRecord snapshot,
            List<String> anomalies) {
        var suffix = "_" + record.getSequence();
        if (record.getOperation() == FiscalRecordOperation.ANULACION) {
            if (snapshot != null) {
                addOnce(anomalies, "INTEGRIDAD_SNAPSHOT_IMPRESION_ANULACION" + suffix);
            }
            if (artifact != null && (artifact.getQrUrl() != null
                    || artifact.getQrHash() != null
                    || artifact.getQrPrefix() != null
                    || artifact.getQrLegend() != null
                    || artifact.getTestNotice() != null)) {
                addOnce(anomalies, "INTEGRIDAD_QR_ANULACION" + suffix);
            }
            return;
        }
        if (snapshot == null) {
            addOnce(anomalies, "INTEGRIDAD_SNAPSHOT_IMPRESION_AUSENTE" + suffix);
            return;
        }

        boolean qrValid = hashMatches(snapshot.getQrUrl(), snapshot.getQrHash())
                && qrUrls.isOfficialUrlFor(
                        snapshot.getQrUrl(), snapshot.getMode(), snapshot.getEnvironment());
        if (qrValid) {
            try {
                qrValid = Objects.equals(snapshot.getQrUrl(), qrUrls.url(
                        record, snapshot.getMode(), snapshot.getEnvironment()));
            } catch (RuntimeException exception) {
                qrValid = false;
            }
        }
        if (!qrValid) {
            addOnce(anomalies, "INTEGRIDAD_SNAPSHOT_IMPRESION_QR" + suffix);
        }

        var expectedLegend = record.getFiscalMode() == FiscalMode.VERIFACTU
                ? FiscalPrintSnapshotFactory.VERIFACTU_LEGEND : null;
        var expectedNotice = snapshot.getEnvironment() == FiscalEndpointEnvironment.TEST
                ? FiscalPrintSnapshotFactory.TEST_NOTICE : null;
        boolean metadataValid = snapshot.getMode() == record.getFiscalMode()
                && snapshot.getEnvironment() != null
                && Objects.equals(snapshot.getFormatVersion(),
                        FiscalPrintSnapshotFactory.FORMAT_VERSION)
                && Objects.equals(snapshot.getGeneratorVersion(), record.getApplicationVersion())
                && Objects.equals(snapshot.getPrefix(), FiscalPrintSnapshotFactory.PREFIX)
                && Objects.equals(snapshot.getLegend(), expectedLegend)
                && Objects.equals(snapshot.getTestNotice(), expectedNotice);
        if (!metadataValid) {
            addOnce(anomalies, "INTEGRIDAD_SNAPSHOT_IMPRESION_METADATOS" + suffix);
        }

        boolean artifactCoherent = artifact != null
                && artifact.getFiscalMode() == snapshot.getMode()
                && artifact.getEnvironment() == snapshot.getEnvironment()
                && Objects.equals(artifact.getQrUrl(), snapshot.getQrUrl())
                && Objects.equals(artifact.getQrHash(), snapshot.getQrHash())
                && Objects.equals(artifact.getQrPrefix(), snapshot.getPrefix())
                && Objects.equals(artifact.getQrLegend(), snapshot.getLegend())
                && Objects.equals(artifact.getTestNotice(), snapshot.getTestNotice())
                && hashMatches(artifact.getQrUrl(), artifact.getQrHash());
        if (!artifactCoherent) {
            addOnce(anomalies, "INTEGRIDAD_SNAPSHOT_IMPRESION_ARTEFACTO" + suffix);
        }
    }

    private static boolean hashMatches(String value, String expectedHash) {
        return value != null && expectedHash != null
                && expectedHash.matches("[0-9A-Fa-f]{64}")
                && expectedHash.equalsIgnoreCase(sha256(value));
    }

    private static void addOnce(List<String> anomalies, String anomaly) {
        if (anomalies instanceof AnomalyCollector collector) {
            collector.addUnique(anomaly);
        } else if (!anomalies.contains(anomaly)) {
            anomalies.add(anomaly);
        }
    }

    private void ensureModeUnchanged(java.util.UUID companyId, FiscalMode expected) {
        var current = configurations.findByCompanyId(companyId)
                .map(VerifactuConfiguration::getCurrentMode).orElse(FiscalMode.PRE_SIF);
        if (current != expected) {
            throw new IllegalStateException("fiscal_integrity_mode_changed");
        }
    }

    private static void ensureWorkerNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("fiscal_integrity_worker_cancelled");
        }
    }

    static long anomalyTotal(List<String> anomalies) {
        return anomalies instanceof AnomalyCollector collector ? collector.total() : anomalies.size();
    }

    static long billingAnomalyTotal(List<String> anomalies) {
        return anomalies instanceof AnomalyCollector collector
                ? collector.categoryTotal("billing")
                : anomalies.stream().filter(FiscalIntegrityService::isBillingAnomaly).count();
    }

    static long eventAnomalyTotal(List<String> anomalies) {
        return anomalies instanceof AnomalyCollector collector
                ? collector.categoryTotal("events")
                : anomalies.stream().filter(FiscalIntegrityService::isEventAnomaly).count();
    }
}

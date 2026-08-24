package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.CompanyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalEventService {
    private final CompanyRepository companies;
    private final InstallationRepository installations;
    private final FiscalSystemVersionRepository systemVersions;
    private final FiscalRecordRepository records;
    private final FiscalEventChainRepository chains;
    private final FiscalEventRepository events;
    private final FiscalEventXmlService xml;
    private final FiscalXadesSigner signer;
    private final FiscalOperatingClockService operatingClock;
    private final FiscalRuntimeProperties runtime;
    private final String producerName;
    private final String producerTaxId;
    private final String systemName;
    private final String systemId;
    private final String systemVersion;

    public FiscalEventService(CompanyRepository companies, InstallationRepository installations,
            FiscalSystemVersionRepository systemVersions,
            FiscalRecordRepository records,
            FiscalEventChainRepository chains, FiscalEventRepository events,
            FiscalEventXmlService xml, FiscalXadesSigner signer,
            FiscalOperatingClockService operatingClock,
            FiscalRuntimeProperties runtime,
            @Value("${tpv.verifactu.producer-name:TPV ERP DEV}") String producerName,
            @Value("${tpv.verifactu.producer-tax-id:B00000000}") String producerTaxId,
            @Value("${tpv.verifactu.system-name:TPV ERP}") String systemName,
            @Value("${tpv.verifactu.system-id:TPVERP}") String systemId,
            @Value("${tpv.verifactu.system-version:4.1.0}") String systemVersion) {
        this.companies = companies;
        this.installations = installations;
        this.systemVersions = systemVersions;
        this.records = records;
        this.chains = chains;
        this.events = events;
        this.xml = xml;
        this.signer = signer;
        this.operatingClock = operatingClock;
        this.runtime = runtime;
        this.producerName = producerName;
        this.producerTaxId = producerTaxId;
        this.systemName = systemName;
        this.systemId = systemId;
        this.systemVersion = systemVersion;
    }

    /** Creates and signs one official RegistroEvento in the append-only event chain. */
    @Transactional
    public FiscalEvent create(UUID companyId, UUID installationId, FiscalMode mode,
            FiscalEventType type, String detail) {
        return createAt(companyId, installationId, mode, type, detail, Instant.now(), null);
    }

    /** Creates an event with export counters frozen into its signed XML. */
    @Transactional
    public FiscalEvent create(UUID companyId, UUID installationId, FiscalMode mode,
            FiscalEventType type, String detail, FiscalEventSummary data) {
        return createAt(companyId, installationId, mode, type, detail, Instant.now(), data);
    }

    /** Emits one summary after six persisted operating hours, excluding downtime. */
    @Transactional
    public FiscalEvent createSummaryIfDue(UUID companyId, UUID installationId, FiscalMode mode,
            Instant now) {
        if (mode != FiscalMode.NO_VERIFACTU) {
            return null;
        }
        var latest = events.findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                companyId, installationId).orElse(null);
        if (latest == null || !operatingClock.observeAndCheckDue(companyId, installationId, now)) {
            return null;
        }
        var summary = createAt(companyId, installationId, mode, FiscalEventType.SUMMARY, null,
                now, null);
        operatingClock.reset(companyId, installationId, now);
        return summary;
    }

    private FiscalEvent createAt(UUID companyId, UUID installationId, FiscalMode mode,
            FiscalEventType type, String detail, Instant now, FiscalEventSummary data) {
        if (mode != FiscalMode.NO_VERIFACTU) {
            return null; // VERI*FACTU does not generate the mandatory event log.
        }
        if (type == FiscalEventType.START_NO_VERIFACTU) {
            operatingClock.reset(companyId, installationId, now);
        }
        var company = companies.findById(companyId)
                .orElseThrow(() -> new IllegalStateException("Empresa fiscal no encontrada"));
        var installation = installations.findById(installationId)
                .orElseThrow(() -> new IllegalStateException("Instalacion fiscal no encontrada"));
        var offset = now.atZone(ZoneId.systemDefault()).toOffsetDateTime();
        var system = new VerifactuSystemInfo(producerName, producerTaxId, systemName, systemId,
                systemVersion, installation.getReferencia(), false, true, false);
        var frozenSystemVersion = systemVersions
                .findByCompanyIdAndInstallationIdAndSystemVersionAndInstallationNumber(
                        companyId, installationId, systemVersion, installation.getReferencia())
                .map(existing -> {
                    if (!existing.matches(producerTaxId, producerName, systemName, systemId,
                            systemVersion, installation.getReferencia(), runtime.isSandbox())) {
                        throw new IllegalStateException(
                                "La identidad fiscal no coincide con la version SIF congelada");
                    }
                    return existing;
                })
                .orElseGet(() -> systemVersions.save(new FiscalSystemVersion(
                        companyId, installationId, producerTaxId, producerName, systemName,
                        systemId, systemVersion, installation.getReferencia(), null,
                        runtime.isSandbox(), now)));
        chains.insertIfMissing(UUID.randomUUID(), companyId, installationId, now);
        var chain = chains.findForUpdate(companyId, installationId)
                .orElseThrow(() -> new IllegalStateException("Cadena de eventos no encontrada"));
        var previousHash = chain.previousHash();
        var sequence = chain.nextSequence();
        var hash = new OfficialHashService().hash(new FiscalEventHashInput(
                producerTaxId, "", systemId, systemVersion, installation.getReferencia(),
                company.getTaxId(), type.code(), previousHash, offset));
        var normalizedDetail = detail == null ? null : detail.trim();
        if (normalizedDetail != null && normalizedDetail.length() > 100) {
            throw new IllegalArgumentException("OtrosDatosEvento no puede superar 100 caracteres");
        }
        var xmlData = type == FiscalEventType.SUMMARY
                ? summary(companyId, installationId, now)
                : data == null ? FiscalEventSummary.empty() : data;
        var unsignedXml = xml.unsignedXml(system, company.getRazonSocial(), company.getTaxId(),
                type, normalizedDetail, offset, previousHash, hash, xmlData);
        var signedXml = signer.signEvent(companyId, installationId, unsignedXml);
        var event = new FiscalEvent(companyId, installationId, frozenSystemVersion.getId(),
                sequence, type, mode, now,
                previousHash, hash, unsignedXml, signedXml, sha256(signedXml), now);
        events.save(event);
        chain.advance(sequence, hash, now);
        chains.save(chain);
        return event;
    }

    @Transactional(readOnly = true)
    public List<FiscalEvent> findTop50(UUID companyId) {
        return events.findTop50ByCompanyIdOrderByGeneratedAtDesc(companyId);
    }

    private FiscalEventSummary summary(UUID companyId, UUID installationId, Instant now) {
        var fiscalEvents = events
                .findAllByCompanyIdAndInstallationIdOrderBySequenceAsc(companyId, installationId);
        var previousSummaryAt = fiscalEvents
                .stream()
                .filter(event -> event.getType() == FiscalEventType.SUMMARY)
                .map(FiscalEvent::getGeneratedAt)
                .max(Instant::compareTo)
                .orElse(Instant.MIN);
        var eventCount = fiscalEvents
                .stream()
                .filter(event -> event.getType() != FiscalEventType.SUMMARY)
                .filter(event -> event.getGeneratedAt().isAfter(previousSummaryAt)
                        && !event.getGeneratedAt().isAfter(now))
                .count();
        var periodRecords = records
                .findAllByCompanyIdAndInstallationIdOrderBySequence(companyId, installationId)
                .stream()
                .filter(record -> record.getFiscalMode() == FiscalMode.NO_VERIFACTU)
                .filter(record -> record.getGeneratedAt().isAfter(previousSummaryAt)
                        && !record.getGeneratedAt().isAfter(now))
                .toList();
        var altaRecords = periodRecords.stream()
                .filter(record -> record.getOperation() == FiscalRecordOperation.ALTA)
                .toList();
        var tax = altaRecords.stream()
                .map(FiscalRecord::getTotalTax)
                .filter(java.util.Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        var amount = altaRecords.stream()
                .map(FiscalRecord::getTotalAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        var cancellations = periodRecords.stream()
                .filter(record -> record.getOperation() == FiscalRecordOperation.ANULACION)
                .count();
        return new FiscalEventSummary(eventCount, altaRecords.size(), tax, amount, cancellations);
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

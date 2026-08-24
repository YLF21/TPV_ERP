package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Instant;
import java.time.OffsetDateTime;
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
        return export(kind, null, null);
    }

    @Transactional
    public FiscalExportView export(FiscalExportKind kind, OffsetDateTime periodStart,
            OffsetDateTime periodEnd) {
        if (kind == null) {
            throw new IllegalArgumentException("El tipo de exportacion es obligatorio");
        }
        if ((periodStart == null) != (periodEnd == null)
                || (periodStart != null && periodEnd.isBefore(periodStart))) {
            throw new IllegalArgumentException(
                    "El periodo de exportacion debe incluir inicio y fin, en ese orden");
        }
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
                    company.getId(), installation.getId()).stream()
                    .filter(record -> mode != FiscalMode.NO_VERIFACTU
                            || record.getFiscalMode() == FiscalMode.NO_VERIFACTU)
                    .filter(record -> inPeriod(record.getGeneratedAt(), periodStart, periodEnd))
                    .toList();
            xml = fiscalRecords.stream().map(record -> artifacts.findByRecordId(record.getId())
                    .map(artifact -> artifact.getSignedXml() == null
                            ? artifact.getUnsignedXml() : artifact.getSignedXml())
                    .orElseThrow(() -> new IllegalStateException(
                            "Registro fiscal sin artefacto congelado: " + record.getId())))
                    .toList();
            if (mode == FiscalMode.NO_VERIFACTU) {
                var event = eventService.create(company.getId(), installation.getId(), mode,
                        FiscalEventType.BILLING_EXPORT, null, billingSummary(fiscalRecords),
                        billingContext(fiscalRecords, periodStart, periodEnd));
                eventId = event.getId();
            }
        } else {
            var fiscalEvents = events.findAllByCompanyIdAndInstallationIdOrderBySequenceAsc(
                    company.getId(), installation.getId()).stream()
                    .filter(event -> inPeriod(event.getGeneratedAt(), periodStart, periodEnd))
                    .toList();
            xml = fiscalEvents.stream().map(FiscalEvent::getSignedXml).toList();
            if (mode == FiscalMode.NO_VERIFACTU) {
                var event = eventService.create(company.getId(), installation.getId(), mode,
                        FiscalEventType.EVENT_EXPORT, null,
                        new FiscalEventSummary(fiscalEvents.size(), 0,
                                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, 0),
                        eventContext(fiscalEvents, periodStart, periodEnd));
                eventId = event.getId();
            }
        }
        var persisted = exports.save(new FiscalExport(company.getId(), installation.getId(), kind,
                eventId, xml.size(), sha256(xml), now, periodStart, periodEnd));
        return new FiscalExportView(persisted.getId(), kind, now,
                persisted.getPeriodStart(), persisted.getPeriodEnd(), xml.size(), eventId, xml);
    }

    private static boolean inPeriod(Instant value, OffsetDateTime start, OffsetDateTime end) {
        if (start == null) {
            return true;
        }
        var instant = value;
        return !instant.isBefore(start.toInstant()) && !instant.isAfter(end.toInstant());
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

    private static FiscalEventSummary billingSummary(List<FiscalRecord> records) {
        var altas = records.stream()
                .filter(record -> record.getOperation() == FiscalRecordOperation.ALTA)
                .toList();
        var tax = altas.stream()
                .map(FiscalRecord::getTotalTax)
                .filter(java.util.Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        var amount = altas.stream()
                .map(FiscalRecord::getTotalAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        var cancellations = records.stream()
                .filter(record -> record.getOperation() == FiscalRecordOperation.ANULACION)
                .count();
        return new FiscalEventSummary(0, altas.size(), tax, amount, cancellations);
    }

    private static FiscalExportContext billingContext(List<FiscalRecord> records,
            OffsetDateTime requestedStart, OffsetDateTime requestedEnd) {
        if (records.isEmpty()) {
            return requestedStart == null
                    ? FiscalExportContext.empty()
                    : new FiscalExportContext(requestedStart, requestedEnd,
                            null, null, null, null);
        }
        var first = records.get(0);
        var last = records.get(records.size() - 1);
        return new FiscalExportContext(
                requestedStart == null
                        ? first.getGeneratedAt().atZone(java.time.ZoneId.of(first.getTimezone()))
                                .toOffsetDateTime()
                        : requestedStart,
                requestedEnd == null
                        ? last.getGeneratedAt().atZone(java.time.ZoneId.of(last.getTimezone()))
                                .toOffsetDateTime()
                        : requestedEnd,
                billingBoundary(first), billingBoundary(last), null, null);
    }

    private static FiscalExportContext.BillingBoundary billingBoundary(FiscalRecord record) {
        return new FiscalExportContext.BillingBoundary(record.getIssuerTaxId(), record.getNumber(),
                record.getIssueDate(), record.getHash());
    }

    private static FiscalExportContext eventContext(List<FiscalEvent> events,
            OffsetDateTime requestedStart, OffsetDateTime requestedEnd) {
        if (events.isEmpty()) {
            return requestedStart == null
                    ? FiscalExportContext.empty()
                    : new FiscalExportContext(requestedStart, requestedEnd,
                            null, null, null, null);
        }
        var first = events.get(0);
        var last = events.get(events.size() - 1);
        return new FiscalExportContext(
                requestedStart == null
                        ? first.getGeneratedAt().atZone(java.time.ZoneId.systemDefault())
                                .toOffsetDateTime()
                        : requestedStart,
                requestedEnd == null
                        ? last.getGeneratedAt().atZone(java.time.ZoneId.systemDefault())
                                .toOffsetDateTime()
                        : requestedEnd,
                null, null, eventBoundary(first), eventBoundary(last));
    }

    private static FiscalExportContext.EventBoundary eventBoundary(FiscalEvent event) {
        return new FiscalExportContext.EventBoundary(event.getType().code(),
                event.getGeneratedAt().atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime(),
                event.getHash());
    }
}

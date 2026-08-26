package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalExportService {
    private static final int MAX_EXPORT_RECORDS = 1000;
    private final CurrentOrganization organization;
    private final InstallationRepository installations;
    private final LicenseRepository licenses;
    private final VerifactuConfigurationRepository configurations;
    private final FiscalRecordRepository records;
    private final FiscalRecordArtifactRepository artifacts;
    private final FiscalEventRepository events;
    private final FiscalExportRepository exports;
    private final FiscalEventService eventService;
    private final VerifactuXmlService fiscalXml;

    @org.springframework.beans.factory.annotation.Autowired
    public FiscalExportService(CurrentOrganization organization, InstallationRepository installations,
            LicenseRepository licenses,
            VerifactuConfigurationRepository configurations, FiscalRecordRepository records,
            FiscalRecordArtifactRepository artifacts, FiscalEventRepository events,
            FiscalEventService eventService,
            FiscalExportRepository exports, VerifactuXmlService fiscalXml) {
        this.organization = organization;
        this.installations = installations;
        this.licenses = licenses;
        this.configurations = configurations;
        this.records = records;
        this.artifacts = artifacts;
        this.events = events;
        this.exports = exports;
        this.eventService = eventService;
        this.fiscalXml = fiscalXml;
    }

    /** Compatibility constructor for focused unit tests and adapters. */
    public FiscalExportService(CurrentOrganization organization, InstallationRepository installations,
            VerifactuConfigurationRepository configurations, FiscalRecordRepository records,
            FiscalRecordArtifactRepository artifacts, FiscalEventRepository events,
            FiscalEventService eventService, FiscalExportRepository exports) {
        this(organization, installations, null, configurations, records, artifacts, events,
                eventService, exports, new VerifactuXmlService());
    }

    @Transactional
    public FiscalExportView export(FiscalExportKind kind) {
        return export(kind, null, null);
    }

    @Transactional
    public FiscalExportView export(FiscalExportRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("La solicitud de exportacion es obligatoria");
        }
        return export(request.kind(), request.periodStart(), request.periodEnd(), null,
                request.safeRecordIds(), request.dateFrom(), request.dateTo(),
                request.documentNumber(), request.operation(), request.documentType(),
                request.fiscalMode(), MAX_EXPORT_RECORDS);
    }

    /**
     * Resolves all request-dependent state synchronously, before Spring hands the response
     * to the async streaming callback. The plan contains records, not their XML payloads.
     */
    @Transactional(readOnly = true)
    public FiscalExportZipPlan prepareExportZip(FiscalExportRequest request) {
        validateRequest(request);
        var company = organization.currentCompany();
        var store = organization.currentStore();
        if (store == null || store.getId() == null || store.getEmpresa() == null
                || !store.getEmpresa().getId().equals(company.getId())) {
            throw new IllegalStateException("La tienda fiscal actual es obligatoria");
        }
        var installation = resolveInstallation(company.getId());
        var mode = configurations.findByCompanyId(company.getId())
                .map(VerifactuConfiguration::getCurrentMode).orElse(FiscalMode.PRE_SIF);
        var requestedRecordIds = request.safeRecordIds();
        if (request.kind() == FiscalExportKind.EVENTS) {
            var selectedEventReferences = events.findExportReferencesByPeriod(
                    company.getId(), installation.getId(),
                    request.periodStart() == null ? null : request.periodStart().toInstant(),
                    request.periodEnd() == null ? null : request.periodEnd().toInstant(),
                    PageRequest.of(0, MAX_EXPORT_RECORDS + 1));
            if (selectedEventReferences.size() > MAX_EXPORT_RECORDS) {
                throw new IllegalArgumentException("fiscal_export_use_export_jobs");
            }
            var selectedEventIds = selectedEventReferences.stream()
                    .map(FiscalEventRepository.FiscalEventExportReference::getId)
                    .toList();
            return new FiscalExportZipPlan(UUID.randomUUID(), company.getId(), store.getId(),
                    installation.getId(), request.kind(), mode, request.periodStart(), request.periodEnd(),
                    List.of(), selectedEventIds, Instant.now());
        }
        var normalizedNumber = normalizeDocumentNumber(request.documentNumber());
        var automaticModeFilter = (requestedRecordIds.isEmpty()
                && request.dateFrom() == null && request.dateTo() == null
                && request.documentNumber() == null && request.operation() == null
                && request.documentType() == null && request.fiscalMode() == null);
        var scopedRecords = requestedRecordIds.isEmpty()
                ? records.findExportBatchByFilters(company.getId(), store.getId(), installation.getId(),
                        request.periodStart() == null ? null : request.periodStart().toInstant(),
                        request.periodEnd() == null ? null : request.periodEnd().toInstant(),
                        request.dateFrom(), request.dateTo(), normalizedNumber,
                        request.operation(), request.documentType(), request.fiscalMode(),
                        automaticModeFilter, mode, PageRequest.of(0, MAX_EXPORT_RECORDS + 1))
                : records.findByCompanyIdAndStoreIdAndInstallationIdAndIdInOrderBySequenceAsc(
                        company.getId(), store.getId(), installation.getId(), requestedRecordIds);
        if (requestedRecordIds.isEmpty() && scopedRecords.size() > MAX_EXPORT_RECORDS) {
            throw new IllegalArgumentException("fiscal_export_use_export_jobs");
        }
        var scopedIds = scopedRecords.stream().map(FiscalRecord::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (!requestedRecordIds.isEmpty()
                && (requestedRecordIds.size() > MAX_EXPORT_RECORDS
                        || requestedRecordIds.stream().distinct().count() != requestedRecordIds.size()
                        || !scopedIds.containsAll(requestedRecordIds))) {
            throw new IllegalArgumentException(
                    "La seleccion contiene identificadores duplicados o fuera de la tienda actual");
        }
        var selected = Set.copyOf(requestedRecordIds);
        var filteredRecords = scopedRecords.stream()
                .filter(record -> !automaticModeFilter || mode != FiscalMode.NO_VERIFACTU
                        || record.getFiscalMode() == FiscalMode.NO_VERIFACTU)
                .filter(record -> inPeriod(record.getGeneratedAt(), request.periodStart(), request.periodEnd()))
                .filter(record -> requestedRecordIds.isEmpty() || selected.contains(record.getId()))
                .filter(record -> request.dateFrom() == null || !record.getIssueDate().isBefore(request.dateFrom()))
                .filter(record -> request.dateTo() == null || !record.getIssueDate().isAfter(request.dateTo()))
                .filter(record -> normalizedNumber == null
                        || record.getNumber().toLowerCase(java.util.Locale.ROOT).contains(normalizedNumber))
                .filter(record -> request.operation() == null || record.getOperation() == request.operation())
                .filter(record -> request.documentType() == null || record.getDocumentType() == request.documentType())
                .filter(record -> request.fiscalMode() == null || record.getFiscalMode() == request.fiscalMode())
                .toList();
        if (!requestedRecordIds.isEmpty() && filteredRecords.size() != requestedRecordIds.size()) {
            throw new IllegalArgumentException("La seleccion no coincide con los filtros solicitados");
        }
        return new FiscalExportZipPlan(UUID.randomUUID(), company.getId(), store.getId(),
                installation.getId(), request.kind(), mode, request.periodStart(), request.periodEnd(),
                filteredRecords, List.of(), Instant.now());
    }

    /** Writes the regulatory export on the server so period exports are not capped at the UI page size. */
    @Transactional(rollbackFor = IOException.class)
    public void writeExportZip(FiscalExportZipPlan plan, OutputStream output) throws IOException {
        if (plan == null || output == null) {
            throw new IllegalArgumentException("El plan y la salida de exportacion son obligatorios");
        }
        var digest = newDigest();
        var count = 0;
        var prefix = plan.kind() == FiscalExportKind.EVENTS ? "evento" : "registro-facturacion";
        var zip = new java.util.zip.ZipOutputStream(output, StandardCharsets.UTF_8);
        if (plan.kind() == FiscalExportKind.BILLING) {
            for (var record : plan.records()) {
                var xml = artifacts.findFrozenXmlByRecordId(record.getId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Registro fiscal sin artefacto congelado: " + record.getId()));
                writeXmlEntry(zip, prefix, ++count, xml, digest, count > 1);
            }
        } else {
            for (var eventId : plan.eventIds()) {
                var xml = events.findSignedXmlByIdAndCompanyIdAndInstallationId(
                                eventId, plan.companyId(), plan.installationId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Evento fiscal no encontrado en la instalacion actual: " + eventId));
                writeXmlEntry(zip, prefix, ++count, xml, digest, count > 1);
            }
        }
        var contentHash = hexDigest(digest);
        zip.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));
        zip.write(manifestJson(plan, contentHash, count).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.finish();

        UUID eventId = null;
        if (plan.mode() == FiscalMode.NO_VERIFACTU) {
            if (plan.kind() == FiscalExportKind.BILLING) {
                var event = eventService.create(plan.companyId(), plan.installationId(), plan.mode(),
                        FiscalEventType.BILLING_EXPORT, null, billingSummary(plan.records()),
                        billingContext(plan.records(), plan.periodStart(), plan.periodEnd()));
                eventId = event == null ? null : event.getId();
            } else {
                var event = eventService.create(plan.companyId(), plan.installationId(), plan.mode(),
                            FiscalEventType.EVENT_EXPORT, null,
                            new FiscalEventSummary(plan.eventIds().size(), 0,
                                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, 0),
                            eventContextForPlan(plan));
                eventId = event == null ? null : event.getId();
            }
        }
        exports.save(new FiscalExport(plan.exportId(), plan.companyId(), plan.installationId(),
                plan.kind(), eventId, count, contentHash, plan.exportedAt(),
                plan.periodStart(), plan.periodEnd()));
    }

    @Transactional
    public FiscalExportView export(FiscalExportKind kind, OffsetDateTime periodStart,
            OffsetDateTime periodEnd) {
        return export(kind, periodStart, periodEnd, null);
    }

    @Transactional
    public FiscalExportView export(FiscalExportKind kind, OffsetDateTime periodStart,
            OffsetDateTime periodEnd, String requirementReference) {
        return export(kind, periodStart, periodEnd, requirementReference, List.of(), null,
                null, null, null, null, null,
                MAX_EXPORT_RECORDS);
    }

    private FiscalExportView export(FiscalExportKind kind, OffsetDateTime periodStart,
            OffsetDateTime periodEnd, String requirementReference, List<UUID> recordIds,
            java.time.LocalDate dateFrom, java.time.LocalDate dateTo, String documentNumber,
            FiscalRecordOperation operation, FiscalDocumentType documentType,
            FiscalMode fiscalMode, int maxRecords) {
        if (kind == null) {
            throw new IllegalArgumentException("El tipo de exportacion es obligatorio");
        }
        if ((periodStart == null) != (periodEnd == null)
                || (periodStart != null && periodEnd.isBefore(periodStart))) {
            throw new IllegalArgumentException(
                    "El periodo de exportacion debe incluir inicio y fin, en ese orden");
        }
        var company = organization.currentCompany();
        var installation = resolveInstallation(company.getId());
        var mode = configurations.findByCompanyId(company.getId())
                .map(VerifactuConfiguration::getCurrentMode).orElse(FiscalMode.PRE_SIF);
        var now = Instant.now();
        var requestedRecordIds = recordIds == null ? List.<UUID>of() : new java.util.ArrayList<>(recordIds);
        if (requestedRecordIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("La seleccion no puede contener identificadores nulos");
        }
        if (requestedRecordIds.size() > 1000) {
            throw new IllegalArgumentException("No se pueden exportar mas de 1000 registros seleccionados");
        }
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new IllegalArgumentException("dateFrom no puede ser posterior a dateTo");
        }
        var normalizedNumber = documentNumber == null || documentNumber.isBlank()
                ? null : documentNumber.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalizedNumber != null && normalizedNumber.length() > 64) {
            throw new IllegalArgumentException("documentNumber no puede superar 64 caracteres");
        }
        if (kind == FiscalExportKind.EVENTS && (!requestedRecordIds.isEmpty()
                || dateFrom != null || dateTo != null || documentNumber != null
                || operation != null || documentType != null || fiscalMode != null)) {
            throw new IllegalArgumentException("Los filtros de registros solo admiten exportaciones BILLING");
        }
        UUID eventId = null;
        List<String> xml;
        List<FiscalRecord> fiscalRecords = List.of();
        Map<UUID, FiscalRecordArtifact> artifactByRecordId = Map.of();
        if (kind == FiscalExportKind.BILLING) {
            var currentStore = organization.currentStore();
            var currentStoreId = currentStore == null ? null : currentStore.getId();
            if (currentStoreId == null && licenses != null) {
                throw new IllegalStateException("La tienda fiscal actual es obligatoria");
            }
            if (licenses != null && (currentStore.getEmpresa() == null
                    || !currentStore.getEmpresa().getId().equals(company.getId()))) {
                throw new IllegalStateException("La tienda fiscal no pertenece a la empresa actual");
            }
            var automaticModeFilter = (requestedRecordIds.isEmpty()
                    && dateFrom == null && dateTo == null && documentNumber == null
                    && operation == null && documentType == null && fiscalMode == null)
                    || requirementReference != null;
            var scopedRecords = requestedRecordIds.isEmpty()
                    ? records.findExportBatchByFilters(company.getId(), currentStoreId, installation.getId(),
                            periodStart == null ? null : periodStart.toInstant(),
                            periodEnd == null ? null : periodEnd.toInstant(), dateFrom, dateTo,
                            normalizedNumber, operation, documentType, fiscalMode,
                            automaticModeFilter, mode, PageRequest.of(0,
                                    Math.toIntExact(Math.min(maxRecords + 1L,
                                            (long) MAX_EXPORT_RECORDS + 1L))))
                    : (currentStoreId == null
                            ? records.findByCompanyIdAndInstallationIdAndIdInOrderBySequenceAsc(
                                    company.getId(), installation.getId(), requestedRecordIds)
                            : records.findByCompanyIdAndStoreIdAndInstallationIdAndIdInOrderBySequenceAsc(
                                    company.getId(), currentStoreId, installation.getId(), requestedRecordIds));
            if (requestedRecordIds.isEmpty() && scopedRecords.size() > maxRecords) {
                throw new IllegalArgumentException("fiscal_export_use_export_jobs");
            }
            if (!requestedRecordIds.isEmpty()
                    && (scopedRecords.size() != requestedRecordIds.size()
                            || requestedRecordIds.stream().distinct().count() != requestedRecordIds.size())) {
                throw new IllegalArgumentException("La seleccion contiene registros fuera de la instalacion actual");
            }
            var selected = Set.copyOf(requestedRecordIds);
            fiscalRecords = scopedRecords.stream()
                    .filter(record -> !automaticModeFilter || mode != FiscalMode.NO_VERIFACTU
                            || record.getFiscalMode() == FiscalMode.NO_VERIFACTU)
                    .filter(record -> inPeriod(record.getGeneratedAt(), periodStart, periodEnd))
                    .filter(record -> requestedRecordIds.isEmpty() || selected.contains(record.getId()))
                    .filter(record -> dateFrom == null || !record.getIssueDate().isBefore(dateFrom))
                    .filter(record -> dateTo == null || !record.getIssueDate().isAfter(dateTo))
                    .filter(record -> normalizedNumber == null
                            || record.getNumber().toLowerCase(java.util.Locale.ROOT).contains(normalizedNumber))
                    .filter(record -> operation == null || record.getOperation() == operation)
                    .filter(record -> documentType == null || record.getDocumentType() == documentType)
                    .filter(record -> fiscalMode == null || record.getFiscalMode() == fiscalMode)
                    .toList();
            if (fiscalRecords.size() > maxRecords) {
                throw new IllegalArgumentException(
                        "La exportacion supera el limite seguro de " + MAX_EXPORT_RECORDS + " registros");
            }
            if (!requestedRecordIds.isEmpty() && fiscalRecords.size() != requestedRecordIds.size()) {
                throw new IllegalArgumentException(
                        "La seleccion no coincide con la modalidad o los filtros solicitados");
            }
            var resolvedArtifacts = fiscalRecords.isEmpty() ? Map.<UUID, FiscalRecordArtifact>of()
                    : artifacts.findAllByRecordIdIn(fiscalRecords.stream()
                            .map(FiscalRecord::getId).toList()).stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    FiscalRecordArtifact::getRecordId,
                                    java.util.function.Function.identity()));
            artifactByRecordId = resolvedArtifacts;
            final var artifactsForExport = resolvedArtifacts;
            xml = fiscalRecords.stream().map(record -> {
                var artifact = artifactsForExport.get(record.getId());
                if (artifact == null) {
                    throw new IllegalStateException(
                            "Registro fiscal sin artefacto congelado: " + record.getId());
                }
                return artifact.getSignedXml() == null
                        ? artifact.getUnsignedXml() : artifact.getSignedXml();
            }).toList();
            if (mode == FiscalMode.NO_VERIFACTU) {
                var event = eventService.create(company.getId(), installation.getId(), mode,
                        FiscalEventType.BILLING_EXPORT, null, billingSummary(fiscalRecords),
                        billingContext(fiscalRecords, periodStart, periodEnd));
                eventId = event.getId();
            }
        } else {
            var fiscalEvents = events.findExportBatchByPeriod(
                    company.getId(), installation.getId(),
                    periodStart == null ? null : periodStart.toInstant(),
                    periodEnd == null ? null : periodEnd.toInstant(),
                    PageRequest.of(0, MAX_EXPORT_RECORDS + 1));
            if (fiscalEvents.size() > MAX_EXPORT_RECORDS) {
                throw new IllegalArgumentException("fiscal_export_use_export_jobs");
            }
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
        String batchXml = null;
        if (requirementReference != null && kind == FiscalExportKind.BILLING
                && mode == FiscalMode.NO_VERIFACTU && !fiscalRecords.isEmpty()) {
            var issuerTaxIds = fiscalRecords.stream().map(FiscalRecord::getIssuerTaxId)
                    .distinct().toList();
            if (issuerTaxIds.size() != 1) {
                throw new IllegalStateException(
                        "Un requerimiento AEAT no puede mezclar NIF emisores");
            }
            final var artifactsForRequirement = artifactByRecordId;
            var signedXml = fiscalRecords.stream().map(record -> {
                var artifact = artifactsForRequirement.get(record.getId());
                var value = artifact == null ? null : artifact.getSignedXml();
                if (value == null || value.isBlank()) {
                    throw new IllegalStateException(
                            "Registro NO VERI*FACTU sin XML firmado congelado: " + record.getId());
                }
                return value;
            }).toList();
            batchXml = fiscalXml.signedRequirementBatchXml(
                    company.getRazonSocial(), issuerTaxIds.getFirst(), signedXml,
                    new FiscalRequirementContext(requirementReference, true));
        }
        var hashValues = batchXml == null ? xml : List.of(batchXml);
        var persisted = exports.save(new FiscalExport(company.getId(), installation.getId(), kind,
                eventId, xml.size(), sha256(hashValues), now, periodStart, periodEnd));
        return new FiscalExportView(persisted.getId(), kind, now,
                persisted.getPeriodStart(), persisted.getPeriodEnd(), xml.size(), eventId, xml,
                batchXml, persisted.getContentHash(), company.getId(),
                exportStoreId(fiscalRecords),
                installation.getId(), fiscalRecords.stream()
                        .map(record -> new FiscalExportRecordView(record.getId(), record.getSequence(),
                                record.getNumber(), record.getGeneratedAt(), record.getHash()))
                        .toList());
    }

    private static boolean inPeriod(Instant value, OffsetDateTime start, OffsetDateTime end) {
        if (start == null) {
            return true;
        }
        var instant = value;
        return !instant.isBefore(start.toInstant()) && !instant.isAfter(end.toInstant());
    }

    private com.tpverp.backend.installation.Installation resolveInstallation(
            UUID companyId) {
        return licenses == null
                ? FiscalInstallationResolver.resolveForCompany(companyId, installations, null)
                : FiscalInstallationResolver.resolveCurrent(organization, installations, licenses);
    }

    private UUID exportStoreId(List<FiscalRecord> fiscalRecords) {
        var recordStoreId = fiscalRecords.stream().findFirst().map(FiscalRecord::getStoreId);
        if (recordStoreId.isPresent()) {
            return recordStoreId.get();
        }
        var store = organization.currentStore();
        return store == null ? null : store.getId();
    }

    private static String manifestJson(FiscalExportView fiscalExport, int files) {
        var first = fiscalExport.records().stream().findFirst();
        var last = fiscalExport.records().isEmpty()
                ? java.util.Optional.<FiscalExportRecordView>empty()
                : java.util.Optional.of(fiscalExport.records().get(fiscalExport.records().size() - 1));
        return "{\n"
                + "  \"exportId\": \"" + fiscalExport.exportId() + "\",\n"
                + "  \"kind\": \"" + fiscalExport.kind() + "\",\n"
                + "  \"exportedAt\": \"" + fiscalExport.exportedAt() + "\",\n"
                + "  \"periodStart\": " + jsonString(fiscalExport.periodStart()) + ",\n"
                + "  \"periodEnd\": " + jsonString(fiscalExport.periodEnd()) + ",\n"
                + "  \"companyId\": " + jsonString(fiscalExport.companyId()) + ",\n"
                + "  \"storeId\": " + jsonString(fiscalExport.storeId()) + ",\n"
                + "  \"installationId\": " + jsonString(fiscalExport.installationId()) + ",\n"
                + "  \"recordCount\": " + fiscalExport.recordCount() + ",\n"
                + "  \"firstRecord\": " + recordJson(first.orElse(null)) + ",\n"
                + "  \"lastRecord\": " + recordJson(last.orElse(null)) + ",\n"
                + "  \"contentHash\": " + jsonString(fiscalExport.contentHash()) + ",\n"
                + "  \"files\": " + files + "\n"
                + "}";
    }

    private static String manifestJson(FiscalExportZipPlan plan, String contentHash, int files) {
        var first = plan.records().isEmpty() ? null : toExportRecord(plan.records().getFirst());
        var last = plan.records().isEmpty()
                ? null : toExportRecord(plan.records().getLast());
        return "{\n"
                + "  \"exportId\": \"" + plan.exportId() + "\",\n"
                + "  \"kind\": \"" + plan.kind() + "\",\n"
                + "  \"exportedAt\": \"" + plan.exportedAt() + "\",\n"
                + "  \"periodStart\": " + jsonString(plan.periodStart()) + ",\n"
                + "  \"periodEnd\": " + jsonString(plan.periodEnd()) + ",\n"
                + "  \"companyId\": " + jsonString(plan.companyId()) + ",\n"
                + "  \"storeId\": " + jsonString(plan.storeId()) + ",\n"
                + "  \"installationId\": " + jsonString(plan.installationId()) + ",\n"
                + "  \"recordCount\": " + files + ",\n"
                + "  \"firstRecord\": " + recordJson(first) + ",\n"
                + "  \"lastRecord\": " + recordJson(last) + ",\n"
                + "  \"contentHash\": " + jsonString(contentHash) + ",\n"
                + "  \"files\": " + files + ",\n"
                + "  \"records\": " + recordsJson(plan) + "\n"
                + "}";
    }

    private static String recordsJson(FiscalExportZipPlan plan) {
        var prefix = plan.kind() == FiscalExportKind.EVENTS ? "evento" : "registro-facturacion";
        return java.util.stream.IntStream.range(0, plan.records().size())
                .mapToObj(index -> {
                    var record = plan.records().get(index);
                    return "{\"file\":\"" + prefix + "-"
                        + String.format(java.util.Locale.ROOT, "%06d", index + 1)
                        + ".xml\",\"recordId\":\"" + record.getId()
                        + "\",\"sequence\":" + record.getSequence() + ",\"number\":\""
                        + jsonEscape(record.getNumber()) + "\",\"generatedAt\":\""
                        + record.getGeneratedAt() + "\",\"hash\":\""
                        + jsonEscape(record.getHash()) + "\"}";
                })
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private FiscalExportContext eventContextForPlan(FiscalExportZipPlan plan) {
        if (plan.eventIds().isEmpty()) {
            return eventContext(List.of(), plan.periodStart(), plan.periodEnd());
        }
        var first = events.findByIdAndCompanyIdAndInstallationId(
                        plan.eventIds().getFirst(), plan.companyId(), plan.installationId())
                .orElseThrow(() -> new IllegalStateException("Primer evento fiscal no encontrado"));
        var last = plan.eventIds().size() == 1 ? first
                : events.findByIdAndCompanyIdAndInstallationId(
                        plan.eventIds().getLast(), plan.companyId(), plan.installationId())
                        .orElseThrow(() -> new IllegalStateException("Ultimo evento fiscal no encontrado"));
        return eventContext(first == last ? List.of(first) : List.of(first, last),
                plan.periodStart(), plan.periodEnd());
    }

    private static FiscalExportRecordView toExportRecord(FiscalRecord record) {
        return new FiscalExportRecordView(record.getId(), record.getSequence(), record.getNumber(),
                record.getGeneratedAt(), record.getHash());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private static String hexDigest(MessageDigest digest) {
        return java.util.HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    private static void writeXmlEntry(java.util.zip.ZipOutputStream zip, String prefix, int index,
            String xml, MessageDigest digest, boolean separator) throws IOException {
        if (xml == null || xml.isBlank()) {
            throw new IllegalStateException("El artefacto fiscal no contiene XML congelado");
        }
        var bytes = xml.getBytes(StandardCharsets.UTF_8);
        if (separator) {
            digest.update((byte) '\n');
        }
        digest.update(bytes);
        zip.putNextEntry(new java.util.zip.ZipEntry(
                prefix + "-" + String.format(java.util.Locale.ROOT, "%06d", index) + ".xml"));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void validateRequest(FiscalExportRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("La solicitud de exportacion es obligatoria");
        }
        if (request.kind() == null) {
            throw new IllegalArgumentException("El tipo de exportacion es obligatorio");
        }
        if ((request.periodStart() == null) != (request.periodEnd() == null)
                || (request.periodStart() != null && request.periodEnd().isBefore(request.periodStart()))) {
            throw new IllegalArgumentException(
                    "El periodo de exportacion debe incluir inicio y fin, en ese orden");
        }
        if (request.dateFrom() != null && request.dateTo() != null
                && request.dateFrom().isAfter(request.dateTo())) {
            throw new IllegalArgumentException("dateFrom no puede ser posterior a dateTo");
        }
        if (request.safeRecordIds().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("La seleccion no puede contener identificadores nulos");
        }
        if (request.safeRecordIds().size() > MAX_EXPORT_RECORDS) {
            throw new IllegalArgumentException("No se pueden exportar mas de 1000 registros seleccionados");
        }
        normalizeDocumentNumber(request.documentNumber());
        if (request.kind() == FiscalExportKind.EVENTS && (!request.safeRecordIds().isEmpty()
                || request.dateFrom() != null || request.dateTo() != null
                || request.documentNumber() != null || request.operation() != null
                || request.documentType() != null || request.fiscalMode() != null)) {
            throw new IllegalArgumentException("Los filtros de registros solo admiten exportaciones BILLING");
        }
    }

    private static String normalizeDocumentNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("documentNumber no puede superar 64 caracteres");
        }
        return normalized;
    }

    private static String recordJson(FiscalExportRecordView record) {
        return record == null ? "null" : "{\"recordId\":\"" + record.recordId()
                + "\",\"sequence\":" + record.sequence() + ",\"number\":\""
                + jsonEscape(record.number()) + "\",\"generatedAt\":\""
                + record.generatedAt() + "\",\"hash\":\"" + jsonEscape(record.hash()) + "\"}";
    }

    private static String jsonString(Object value) {
        return value == null ? "null" : "\"" + jsonEscape(String.valueOf(value)) + "\"";
    }

    private static String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
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
        var firstGeneratedAt = persistedEventTimestamp(first);
        var lastGeneratedAt = first == last
                ? firstGeneratedAt : persistedEventTimestamp(last);
        return new FiscalExportContext(
                requestedStart == null
                        ? firstGeneratedAt
                        : requestedStart,
                requestedEnd == null
                        ? lastGeneratedAt
                        : requestedEnd,
                null, null,
                eventBoundary(first, firstGeneratedAt),
                eventBoundary(last, lastGeneratedAt));
    }

    private static FiscalExportContext.EventBoundary eventBoundary(
            FiscalEvent event, OffsetDateTime generatedAt) {
        return new FiscalExportContext.EventBoundary(event.getType().code(),
                generatedAt, event.getHash());
    }

    private static OffsetDateTime persistedEventTimestamp(FiscalEvent event) {
        var source = event.getUnsignedXml();
        if (source == null || source.isBlank()) {
            throw new IllegalStateException(
                    "Registro de evento fiscal sin XML congelado: " + event.getId());
        }
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                    source.getBytes(StandardCharsets.UTF_8)));
            var values = document.getElementsByTagNameNS("*", "FechaHoraHusoGenEvento");
            if (values.getLength() != 1) {
                throw new IllegalStateException(
                        "Registro de evento fiscal sin hora congelada unica: " + event.getId());
            }
            var persisted = OffsetDateTime.parse(values.item(0).getTextContent().trim());
            if (!persisted.toInstant().equals(
                    event.getGeneratedAt().truncatedTo(ChronoUnit.SECONDS))) {
                throw new IllegalStateException(
                        "La hora congelada del evento fiscal no coincide con su registro: "
                                + event.getId());
            }
            return persisted;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "No se pudo leer la hora congelada del evento fiscal: " + event.getId(),
                    exception);
        }
    }

    public record FiscalExportZipPlan(
            UUID exportId,
            UUID companyId,
            UUID storeId,
            UUID installationId,
            FiscalExportKind kind,
            FiscalMode mode,
            OffsetDateTime periodStart,
            OffsetDateTime periodEnd,
            List<FiscalRecord> records,
            List<UUID> eventIds,
            Instant exportedAt) {
        public FiscalExportZipPlan {
            records = records == null ? List.of() : List.copyOf(records);
            eventIds = eventIds == null ? List.of() : List.copyOf(eventIds);
        }
    }
}

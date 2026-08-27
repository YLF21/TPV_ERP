package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CompanyRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.StoreRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalEventService {
    private final CompanyRepository companies;
    private final StoreRepository stores;
    private final LicenseRepository licenses;
    private final InstallationRepository installations;
    private final FiscalSystemVersionRepository systemVersions;
    private final FiscalRecordRepository records;
    private final FiscalEventChainRepository chains;
    private final FiscalEventRepository events;
    private final FiscalEventXmlService xml;
    private final FiscalXadesSigner signer;
    private final FiscalOperatingClockService operatingClock;
    private final FiscalRuntimeProperties runtime;
    private final Clock clock;
    private final String producerName;
    private final String producerTaxId;
    private final String systemName;
    private final String systemId;
    private final String systemVersion;

    public FiscalEventService(CompanyRepository companies, StoreRepository stores,
            LicenseRepository licenses,
            InstallationRepository installations,
            FiscalSystemVersionRepository systemVersions,
            FiscalRecordRepository records,
            FiscalEventChainRepository chains, FiscalEventRepository events,
            FiscalEventXmlService xml, FiscalXadesSigner signer,
            FiscalOperatingClockService operatingClock,
            FiscalRuntimeProperties runtime,
            Clock clock,
            @Value("${tpv.verifactu.producer-name:TPV ERP DEV}") String producerName,
            @Value("${tpv.verifactu.producer-tax-id:B00000000}") String producerTaxId,
            @Value("${tpv.verifactu.system-name:TPV ERP}") String systemName,
            @Value("${tpv.verifactu.system-id:TPVERP}") String systemId,
            @Value("${tpv.verifactu.system-version:4.1.0}") String systemVersion) {
        this.companies = companies;
        this.stores = stores;
        this.licenses = licenses;
        this.installations = installations;
        this.systemVersions = systemVersions;
        this.records = records;
        this.chains = chains;
        this.events = events;
        this.xml = xml;
        this.signer = signer;
        this.operatingClock = operatingClock;
        this.runtime = runtime;
        this.clock = clock;
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
        return createAt(companyId, installationId, mode, type, detail, Instant.now(clock), null,
                FiscalExportContext.empty());
    }

    /** Creates an event with export counters frozen into its signed XML. */
    @Transactional
    public FiscalEvent create(UUID companyId, UUID installationId, FiscalMode mode,
            FiscalEventType type, String detail, FiscalEventSummary data) {
        return createAt(companyId, installationId, mode, type, detail, Instant.now(clock), data,
                FiscalExportContext.empty());
    }

    /** Creates an event with counters and real period boundaries frozen into its XML. */
    @Transactional
    public FiscalEvent create(UUID companyId, UUID installationId, FiscalMode mode,
            FiscalEventType type, String detail, FiscalEventSummary data,
            FiscalExportContext exportContext) {
        return createAt(companyId, installationId, mode, type, detail, Instant.now(clock), data,
                exportContext);
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
                now, null, FiscalExportContext.empty());
        operatingClock.reset(companyId, installationId, now);
        return summary;
    }

    /**
     * Persists the last event summary before an orderly application shutdown.
     *
     * The event-chain row is locked before checking the last event. This makes
     * repeated shutdown callbacks (or a callback racing the periodic scheduler)
     * idempotent for one company/installation pair.
     */
    @Transactional
    public FiscalEvent createSummaryBeforeShutdown(UUID companyId, UUID installationId,
            FiscalMode mode, Instant now) {
        if (mode != FiscalMode.NO_VERIFACTU) {
            return null;
        }
        var generatedAt = java.util.Objects.requireNonNull(now, "now")
                .truncatedTo(ChronoUnit.SECONDS);
        // Do not create an otherwise empty chain for a tenant that has never
        // emitted an event. There is no summary to persist in that case.
        if (events.findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                companyId, installationId).isEmpty()) {
            return null;
        }
        chains.insertIfMissing(UUID.randomUUID(), companyId, installationId, generatedAt);
        chains.findForUpdate(companyId, installationId)
                .orElseThrow(() -> new IllegalStateException("Cadena de eventos no encontrada"));
        var latest = events.findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                companyId, installationId).orElse(null);
        if (latest == null || latest.getType() == FiscalEventType.SUMMARY) {
            return null;
        }
        var summary = createAt(companyId, installationId, mode, FiscalEventType.SUMMARY, null,
                generatedAt, null, FiscalExportContext.empty());
        operatingClock.reset(companyId, installationId, generatedAt);
        return summary;
    }

    private FiscalEvent createAt(UUID companyId, UUID installationId, FiscalMode mode,
            FiscalEventType type, String detail, Instant now, FiscalEventSummary data,
            FiscalExportContext exportContext) {
        if (mode != FiscalMode.NO_VERIFACTU) {
            return null; // VERI*FACTU does not generate the mandatory event log.
        }
        // AEAT event timestamps have second precision. Freeze that exact instant
        // before deriving the offset, hash, XML or persisted chain state.
        var generatedAt = java.util.Objects.requireNonNull(now, "now")
                .truncatedTo(ChronoUnit.SECONDS);
        if (type == FiscalEventType.START_NO_VERIFACTU) {
            operatingClock.reset(companyId, installationId, generatedAt);
        }
        var company = companies.findById(companyId)
                .orElseThrow(() -> new IllegalStateException("Empresa fiscal no encontrada"));
        var installation = installations.findById(installationId)
                .orElseThrow(() -> new IllegalStateException("Instalacion fiscal no encontrada"));
        var offset = generatedAt.atZone(fiscalZone(companyId, installationId)).toOffsetDateTime();
        var system = new VerifactuSystemInfo(producerName, producerTaxId, systemName, systemId,
                systemVersion, installation.getReferencia(), false, false, false);
        var frozenSystemVersion = systemVersions
                .findByCompanyIdAndInstallationIdAndSystemVersionAndInstallationNumber(
                        companyId, installationId, systemVersion, installation.getReferencia())
                .map(existing -> {
                    if (!existing.matches(producerTaxId, producerName, systemName, systemId,
                            systemVersion, installation.getReferencia(),
                            runtime.declarationHash(), runtime.isSandbox())) {
                        throw new IllegalStateException(
                                "La identidad fiscal no coincide con la version SIF congelada");
                    }
                    return existing;
                })
                .orElseGet(() -> systemVersions.save(new FiscalSystemVersion(
                        companyId, installationId, producerTaxId, producerName, systemName,
                        systemId, systemVersion, installation.getReferencia(),
                        runtime.declarationHash(),
                        runtime.isSandbox(), generatedAt)));
        chains.insertIfMissing(UUID.randomUUID(), companyId, installationId, generatedAt);
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
                ? summary(companyId, installationId, generatedAt)
                : data == null ? FiscalEventSummary.empty() : data;
        var unsignedXml = xml.unsignedXml(system, company.getRazonSocial(), company.getTaxId(),
                type, normalizedDetail, offset, previousHash, hash, xmlData, exportContext);
        var signedXml = signer.signEvent(companyId, installationId, unsignedXml);
        var event = new FiscalEvent(companyId, installationId, frozenSystemVersion.getId(),
                sequence, type, mode, generatedAt,
                previousHash, hash, unsignedXml, signedXml, sha256(signedXml), generatedAt);
        events.save(event);
        chain.advance(sequence, hash, generatedAt);
        chains.save(chain);
        return event;
    }

    @Transactional(readOnly = true)
    public List<FiscalEvent> findTop50(UUID companyId) {
        var installation = FiscalInstallationResolver.resolveForCompany(
                companyId, installations, licenses);
        return findTop50(companyId, installation.getId());
    }

    /**
     * Reads the event chain for the operational store context. The resolver
     * deliberately receives the current store, so a company with several
     * fiscal installations cannot leak another store's events into this API.
     */
    @Transactional(readOnly = true)
    public List<FiscalEvent> findTop50Current(CurrentOrganization organization) {
        var store = organization.currentStore();
        var company = store.getEmpresa();
        if (company == null) {
            throw new IllegalStateException("La tienda actual no tiene empresa fiscal");
        }
        var installation = FiscalInstallationResolver.resolveCurrent(
                organization, installations, licenses);
        return findTop50(company.getId(), installation.getId());
    }

    /**
     * Reads only event metadata for APP GESTIÓN. The XML columns are deliberately
     * excluded from the projection and the database applies the visible cap.
     */
    @Transactional(readOnly = true)
    public List<FiscalEventView> findTop50ViewsCurrent(CurrentOrganization organization) {
        var store = organization.currentStore();
        var company = store.getEmpresa();
        if (company == null) {
            throw new IllegalStateException("La tienda actual no tiene empresa fiscal");
        }
        var installation = FiscalInstallationResolver.resolveCurrent(
                organization, installations, licenses);
        return events.findTop50ViewsByCompanyIdAndInstallationId(
                company.getId(), installation.getId(), PageRequest.of(0, 50));
    }

    /** Keyset page for APP GESTIÓN event history, frozen at the first page's sequence. */
    @Transactional(readOnly = true)
    public FiscalEventReadCursorPage findCursorViewsCurrent(
            CurrentOrganization organization, int size, String encodedCursor) {
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size debe estar entre 1 y 100");
        }
        var store = organization.currentStore();
        var company = store.getEmpresa();
        if (company == null) {
            throw new IllegalStateException("La tienda actual no tiene empresa fiscal");
        }
        var installation = FiscalInstallationResolver.resolveCurrent(
                organization, installations, licenses);
        var fingerprint = FiscalEventReadCursorCodec.fingerprint(
                "EVENTS", company.getId(), store.getId(), installation.getId());
        var cursor = encodedCursor == null || encodedCursor.isBlank()
                ? null : FiscalEventReadCursorCodec.decode(encodedCursor);
        if (cursor != null && !java.util.Objects.equals(cursor.scopeFingerprint(), fingerprint)) {
            throw new IllegalArgumentException("cursor no corresponde al alcance actual");
        }
        var snapshot = cursor == null
                ? events.maxSequenceForRead(company.getId(), installation.getId())
                : cursor.snapshotSequence();
        var rows = events.findCursorViewsForRead(company.getId(), installation.getId(), snapshot,
                cursor, size + 1);
        var hasExtra = rows.size() > size;
        var visible = new java.util.ArrayList<>(rows.subList(0, Math.min(size, rows.size())));
        var direction = cursor == null ? FiscalEventReadCursor.Direction.NEXT : cursor.direction();
        if (direction == FiscalEventReadCursor.Direction.PREVIOUS) {
            java.util.Collections.reverse(visible);
        }
        var hasPrevious = direction == FiscalEventReadCursor.Direction.PREVIOUS
                ? hasExtra : cursor != null && !visible.isEmpty();
        var hasNext = direction == FiscalEventReadCursor.Direction.NEXT
                ? hasExtra : cursor != null && !visible.isEmpty();
        var previousCursor = hasPrevious ? FiscalEventReadCursorCodec.encode(
                new FiscalEventReadCursor(snapshot, visible.getFirst().sequence(),
                        visible.getFirst().id(), FiscalEventReadCursor.Direction.PREVIOUS,
                        fingerprint)) : null;
        var nextCursor = hasNext ? FiscalEventReadCursorCodec.encode(
                new FiscalEventReadCursor(snapshot, visible.getLast().sequence(),
                        visible.getLast().id(), FiscalEventReadCursor.Direction.NEXT,
                        fingerprint)) : null;
        return new FiscalEventReadCursorPage(visible, size, nextCursor, previousCursor,
                hasNext, hasPrevious, snapshot);
    }

    /** Reads a single, explicitly scoped company/installation event chain. */
    @Transactional(readOnly = true)
    public List<FiscalEvent> findTop50(UUID companyId, UUID installationId) {
        java.util.Objects.requireNonNull(companyId, "companyId");
        java.util.Objects.requireNonNull(installationId, "installationId");
        return events.findTop50ByCompanyIdAndInstallationIdOrderByGeneratedAtDesc(
                companyId, installationId);
    }

    private FiscalEventSummary summary(UUID companyId, UUID installationId, Instant now) {
        var aggregate = events.summarizeEvents(companyId, installationId, now);
        if (aggregate != null) {
            var recordsAggregate = records.summarizePeriod(
                    companyId, installationId, aggregate.previousSummaryAt(), now);
            if (recordsAggregate != null) {
                return new FiscalEventSummary(
                        aggregate.eventCount(), recordsAggregate.altaCount(),
                        recordsAggregate.totalTax(), recordsAggregate.totalAmount(),
                        recordsAggregate.cancellationCount());
            }
        }
        // Compatibility fallback for older test doubles; the real JDBC fragments above
        // always return aggregates and never materialize the fiscal history.
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

    private ZoneId fiscalZone(UUID companyId, UUID installationId) {
        var frozenRecordTimezone = records
                .findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                        companyId, installationId)
                .map(FiscalRecord::getTimezone);
        var timezone = frozenRecordTimezone.orElseGet(() -> {
            var licensedStoreTimezones = licenses
                    .findActiveStoreTimezonesByCompanyIdAndInstallationId(
                            companyId, installationId)
                    .stream()
                    .distinct()
                    .toList();
            if (licensedStoreTimezones.size() > 1) {
                throw new IllegalStateException(
                        "La instalacion tiene licencias activas con timezones fiscales distintas");
            }
            if (licensedStoreTimezones.size() == 1) {
                return licensedStoreTimezones.getFirst();
            }
            var persistedStoreTimezones = stores.findByEmpresaId(companyId).stream()
                    .map(com.tpverp.backend.organization.Store::getTimezone)
                    .distinct()
                    .toList();
            if (persistedStoreTimezones.size() != 1) {
                throw new IllegalStateException(
                        "No se puede determinar una timezone fiscal unica para la empresa");
            }
            return persistedStoreTimezones.getFirst();
        });
        try {
            return ZoneId.of(timezone);
        } catch (java.time.DateTimeException exception) {
            throw new IllegalStateException("Timezone fiscal persistida no valida", exception);
        }
    }
}

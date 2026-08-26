package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application service for the general, read-only fiscal-record catalogue. */
@Service
public class FiscalRecordReadService {

    private static final int MAX_PAGE_SIZE = 100;

    private final CurrentOrganization organization;
    private final InstallationRepository installations;
    private final LicenseRepository licenses;
    private final FiscalRecordReadRepository reads;
    private final Clock clock;

    @Autowired
    public FiscalRecordReadService(
            CurrentOrganization organization,
            InstallationRepository installations,
            LicenseRepository licenses,
            FiscalRecordReadRepository reads,
            Clock clock) {
        this.organization = organization;
        this.installations = installations;
        this.licenses = licenses;
        this.reads = reads;
        this.clock = clock;
    }

    /** Compatibility constructor for focused tests and adapters. */
    public FiscalRecordReadService(
            CurrentOrganization organization,
            InstallationRepository installations,
            LicenseRepository licenses,
            FiscalRecordReadRepository reads) {
        this(organization, installations, licenses, reads, Clock.systemUTC());
    }

    @Transactional(readOnly = true)
    public FiscalRecordReadPage records(
            LocalDate dateFrom,
            LocalDate dateTo,
            FiscalRecordOperation operation,
            FiscalDocumentType documentType,
            String documentNumber,
            FiscalMode fiscalMode,
            int page,
            int size) {
        validatePage(page, size);
        validateRange(dateFrom, dateTo);
        var normalizedNumber = normalizeDocumentNumber(documentNumber);
        var scope = scope();
        return reads.findPage(scope.companyId(), scope.storeId(), scope.installationId(),
                dateFrom, dateTo, operation, documentType, normalizedNumber, fiscalMode,
                page, size);
    }

    @Transactional(readOnly = true)
    public FiscalRecordReadPage records(
            LocalDate dateFrom,
            LocalDate dateTo,
            FiscalRecordOperation operation,
            FiscalDocumentType documentType,
            String documentNumber,
            FiscalRecordNumberMatch numberMatch,
            FiscalMode fiscalMode,
            int page,
            int size) {
        validatePage(page, size);
        validateRange(dateFrom, dateTo);
        var normalizedNumber = normalizeDocumentNumber(documentNumber);
        var normalizedMatch = normalizeNumberMatch(numberMatch);
        var scope = scope();
        return reads.findPage(scope.companyId(), scope.storeId(), scope.installationId(),
                dateFrom, dateTo, operation, documentType, normalizedNumber, normalizedMatch,
                fiscalMode, page, size);
    }

    @Transactional(readOnly = true)
    public FiscalRecordReadCursorPage recordsCursor(
            LocalDate dateFrom,
            LocalDate dateTo,
            FiscalRecordOperation operation,
            FiscalDocumentType documentType,
            String documentNumber,
            FiscalMode fiscalMode,
            int size,
            String encodedCursor) {
        return recordsCursor(dateFrom, dateTo, operation, documentType, documentNumber,
                FiscalRecordNumberMatch.PREFIX, fiscalMode, size, encodedCursor);
    }

    @Transactional(readOnly = true)
    public FiscalRecordReadCursorPage recordsCursor(
            LocalDate dateFrom,
            LocalDate dateTo,
            FiscalRecordOperation operation,
            FiscalDocumentType documentType,
            String documentNumber,
            FiscalRecordNumberMatch numberMatch,
            FiscalMode fiscalMode,
            int size,
            String encodedCursor) {
        validateCursorSize(size);
        validateRange(dateFrom, dateTo);
        var normalizedNumber = normalizeDocumentNumber(documentNumber);
        var normalizedMatch = normalizeNumberMatch(numberMatch);
        var scope = scope();
        var fingerprint = FiscalRecordReadCursorCodec.fingerprint(
                scope.companyId(), scope.storeId(), scope.installationId(), dateFrom, dateTo,
                operation, documentType, normalizedNumber, normalizedMatch, fiscalMode);
        FiscalRecordReadCursor cursor = encodedCursor == null || encodedCursor.isBlank()
                ? null : FiscalRecordReadCursorCodec.decode(encodedCursor);
        if (cursor != null && !java.util.Objects.equals(cursor.filterFingerprint(), fingerprint)) {
            throw new IllegalArgumentException("cursor no corresponde a los filtros actuales");
        }
        var snapshotSequence = cursor == null
                ? reads.maxSequence(scope.companyId(), scope.storeId(), scope.installationId())
                : cursor.snapshotSequence();
        var rows = reads.findCursorRows(scope.companyId(), scope.storeId(), scope.installationId(),
                dateFrom, dateTo, operation, documentType, normalizedNumber, normalizedMatch,
                fiscalMode, snapshotSequence, cursor, size + 1);
        var hasExtra = rows.size() > size;
        var visible = new java.util.ArrayList<>(rows.subList(0, Math.min(size, rows.size())));
        var direction = cursor == null ? FiscalRecordReadCursor.Direction.NEXT : cursor.direction();
        if (direction == FiscalRecordReadCursor.Direction.PREVIOUS) {
            java.util.Collections.reverse(visible);
        }
        var hasPrevious = direction == FiscalRecordReadCursor.Direction.PREVIOUS
                ? hasExtra : cursor != null && !visible.isEmpty();
        var hasNext = direction == FiscalRecordReadCursor.Direction.NEXT
                ? hasExtra : cursor != null && !visible.isEmpty();
        var previousCursor = hasPrevious
                ? FiscalRecordReadCursorCodec.encode(new FiscalRecordReadCursor(
                        snapshotSequence, visible.getFirst().sequence(),
                        FiscalRecordReadCursor.Direction.PREVIOUS, fingerprint)) : null;
        var nextCursor = hasNext
                ? FiscalRecordReadCursorCodec.encode(new FiscalRecordReadCursor(
                        snapshotSequence, visible.getLast().sequence(),
                        FiscalRecordReadCursor.Direction.NEXT, fingerprint)) : null;
        return new FiscalRecordReadCursorPage(
                visible.stream().map(FiscalRecordReadView::from).toList(), size,
                nextCursor, previousCursor, hasNext, hasPrevious, snapshotSequence);
    }

    @Transactional(readOnly = true)
    public FiscalRecordDetailView record(UUID recordId) {
        if (recordId == null) {
            throw new IllegalArgumentException("recordId es obligatorio");
        }
        var scope = scope();
        var row = reads.findDetail(
                        scope.companyId(), scope.storeId(), scope.installationId(), recordId)
                .orElseThrow(() -> new NoSuchElementException("Registro fiscal no encontrado"));
        var relations = reads.findRelations(
                scope.companyId(), scope.storeId(), scope.installationId(), recordId);
        return toDetail(row, relations);
    }

    private Scope scope() {
        var store = organization.currentStore();
        var company = store.getEmpresa();
        if (company == null) {
            throw new IllegalStateException("La empresa de la tienda no esta inicializada");
        }
        var installation = FiscalInstallationResolver.resolveCurrent(
                organization, installations, licenses);
        return new Scope(company.getId(), store.getId(), installation.getId());
    }

    private FiscalRecordDetailView toDetail(
            FiscalRecordReadRepository.Row row,
            List<FiscalRecordRelationView> relations) {
        var detail = row.detail();
        if (detail == null) {
            throw new IllegalStateException("La consulta de detalle no devolvio sus metadatos");
        }
        var document = detail.documentId() == null
                ? null
                : new FiscalRecordDocumentView(
                        detail.documentId(), detail.documentStoreId(), detail.documentType(),
                        detail.documentStatus(), detail.documentNumber(), detail.documentIssueDate(),
                        detail.documentCreatedAt(), detail.documentConfirmedAt(),
                        detail.documentCancelledAt());
        var artifact = detail.artifactMode() == null
                ? null
                : new FiscalRecordArtifactView(
                        detail.artifactMode(), detail.artifactEnvironment(), detail.artifactSandbox(),
                        detail.artifactSystemVersionId(), detail.artifactIssuerName(),
                        detail.artifactIssuerTaxId(), detail.artifactXmlHash(), detail.artifactQrUrl(),
                        detail.artifactQrHash(), detail.artifactCreatedAt());
        var submission = row.submissionStatus() == null
                ? null
                : new FiscalRecordSubmissionView(
                        row.submissionStatus(), row.submissionUpdatedAt(), detail.submissionErrorCode());
        return new FiscalRecordDetailView(
                row.recordId(), detail.chainId(), detail.companyId(), row.installationId(), row.storeId(),
                row.documentId(), row.sequence(), row.operation(), row.documentType(), row.number(),
                row.issueDate(), row.generatedAt(), detail.timezone(), detail.issuerTaxId(),
                row.totalTax(), row.totalAmount(), row.previousHash(), row.hash(), detail.snapshotHash(),
                detail.formatVersion(), detail.algorithmVersion(), detail.applicationVersion(),
                row.fiscalMode(), navigableNeighbor(detail.previousRecordId(), detail.previousRecordStoreId(),
                        row.storeId()),
                navigableNeighbor(detail.nextRecordId(), detail.nextRecordStoreId(), row.storeId()),
                document, artifact,
                relations, submission, adjacentChainStatus(row));
    }

    private static UUID navigableNeighbor(UUID neighborId, UUID neighborStoreId, UUID currentStoreId) {
        return neighborId != null && currentStoreId.equals(neighborStoreId) ? neighborId : null;
    }

    /**
     * Reports only the two adjacent links returned by the scoped backend query. This is
     * deliberately not a complete cryptographic-chain verification.
     */
    private String adjacentChainStatus(FiscalRecordReadRepository.Row row) {
        var detail = row.detail();
        if (row.hash() == null || row.generatedAt() == null || row.sequence() < 1) {
            return "ADJACENT_UNAVAILABLE";
        }
        var now = Instant.now(clock);
        if (row.generatedAt().isAfter(now)) {
            return "ADJACENT_ANOMALOUS";
        }
        if (detail.previousRecordId() == null && row.sequence() > 1) {
            return "ADJACENT_ANOMALOUS";
        }
        if (detail.previousRecordId() != null && (detail.previousRecordHash() == null
                || detail.previousRecordGeneratedAt() == null || row.previousHash() == null
                || detail.previousRecordGeneratedAt().isAfter(now)
                || detail.previousRecordGeneratedAt().isAfter(row.generatedAt())
                || !detail.previousRecordHash().equalsIgnoreCase(row.previousHash()))) {
            return "ADJACENT_ANOMALOUS";
        }
        boolean previousOk = detail.previousRecordId() == null
                && row.sequence() == 1 && row.previousHash() == null
                || detail.previousRecordId() != null;
        boolean nextOk = detail.nextRecordId() == null
                || (detail.nextRecordPreviousHash() != null
                        && detail.nextRecordGeneratedAt() != null
                        && !detail.nextRecordGeneratedAt().isAfter(now)
                        && !detail.nextRecordGeneratedAt().isBefore(row.generatedAt())
                        && detail.nextRecordPreviousHash().equalsIgnoreCase(row.hash()));
        if (previousOk && nextOk) {
            return "ADJACENT_VALID";
        }
        if (detail.nextRecordId() != null && (detail.nextRecordPreviousHash() == null
                || detail.nextRecordGeneratedAt() == null)) {
            return "ADJACENT_ANOMALOUS";
        }
        return "ADJACENT_ANOMALOUS";
    }

    private static void validatePage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page no puede ser negativo");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size debe estar entre 1 y " + MAX_PAGE_SIZE);
        }
    }

    private static void validateCursorSize(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size debe estar entre 1 y " + MAX_PAGE_SIZE);
        }
    }

    private static void validateRange(LocalDate dateFrom, LocalDate dateTo) {
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new IllegalArgumentException("dateFrom no puede ser posterior a dateTo");
        }
    }

    private static String normalizeDocumentNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = value.trim();
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("documentNumber no puede superar 64 caracteres");
        }
        return normalized;
    }

    private static FiscalRecordNumberMatch normalizeNumberMatch(FiscalRecordNumberMatch value) {
        return value == null ? FiscalRecordNumberMatch.PREFIX : value;
    }

    private record Scope(UUID companyId, UUID storeId, UUID installationId) {
    }
}

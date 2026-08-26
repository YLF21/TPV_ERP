package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only fiscal history facade for APP GESTION. Scope is always resolved
 * from the authenticated operational company and its fiscal installation;
 * callers cannot provide an arbitrary company or installation identifier.
 */
@Service
@Transactional(readOnly = true)
public class FiscalHistoryReadService {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 100;

    private final CurrentOrganization organization;
    private final InstallationRepository installations;
    private final LicenseRepository licenses;
    private final FiscalExportRepository exports;
    private final FiscalRequiredSubmissionRepository submissions;

    public FiscalHistoryReadService(CurrentOrganization organization,
            InstallationRepository installations, LicenseRepository licenses,
            FiscalExportRepository exports,
            FiscalRequiredSubmissionRepository submissions) {
        this.organization = organization;
        this.installations = installations;
        this.licenses = licenses;
        this.exports = exports;
        this.submissions = submissions;
    }

    public List<FiscalExportHistoryView> exports(Integer requestedLimit) {
        int limit = safeLimit(requestedLimit);
        var scope = scope();
        return exports.findLegacyHistoryPage(scope.companyId(), scope.installationId(), limit);
    }

    public List<FiscalRequiredSubmissionHistoryView> requiredSubmissions(Integer requestedLimit) {
        int limit = safeLimit(requestedLimit);
        var scope = scope();
        return submissions.findLegacyHistoryPage(scope.companyId(), scope.installationId(), limit);
    }

    @Transactional(readOnly = true)
    public FiscalHistoryReadCursorPage<FiscalExportHistoryView> exportsCursor(
            Integer requestedSize, String encodedCursor) {
        int size = safeLimit(requestedSize);
        var scope = scope();
        var fingerprint = FiscalHistoryReadCursorCodec.fingerprint(
                "EXPORTS", scope.companyId(), scope.storeId(), scope.installationId());
        var cursor = decodeCursor(encodedCursor, fingerprint);
        var rows = exports.findHistoryCursorPage(scope.companyId(), scope.installationId(),
                cursor, size + 1);
        return cursorPage(rows, size, cursor, fingerprint,
                FiscalExportHistoryView::exportedAt, FiscalExportHistoryView::exportId);
    }

    @Transactional(readOnly = true)
    public FiscalHistoryReadCursorPage<FiscalRequiredSubmissionHistoryView> requiredSubmissionsCursor(
            Integer requestedSize, String encodedCursor) {
        int size = safeLimit(requestedSize);
        var scope = scope();
        var fingerprint = FiscalHistoryReadCursorCodec.fingerprint(
                "REQUIRED_SUBMISSIONS", scope.companyId(), scope.storeId(), scope.installationId());
        var cursor = decodeCursor(encodedCursor, fingerprint);
        var rows = submissions.findHistoryCursorPage(scope.companyId(), scope.installationId(),
                cursor, size + 1);
        return cursorPage(rows, size, cursor, fingerprint,
                FiscalRequiredSubmissionHistoryView::requestedAt,
                FiscalRequiredSubmissionHistoryView::id);
    }

    private static FiscalHistoryReadCursor decodeCursor(String encoded, String fingerprint) {
        var cursor = encoded == null || encoded.isBlank()
                ? null : FiscalHistoryReadCursorCodec.decode(encoded);
        if (cursor != null && !java.util.Objects.equals(cursor.scopeFingerprint(), fingerprint)) {
            throw new IllegalArgumentException("cursor no corresponde al alcance actual");
        }
        return cursor;
    }

    private static <T> FiscalHistoryReadCursorPage<T> cursorPage(
            List<T> rows, int size, FiscalHistoryReadCursor cursor, String fingerprint,
            java.util.function.Function<T, java.time.Instant> timestamp,
            java.util.function.Function<T, UUID> id) {
        boolean hasExtra = rows.size() > size;
        var visible = new java.util.ArrayList<>(rows.subList(0, Math.min(size, rows.size())));
        var direction = cursor == null ? FiscalHistoryReadCursor.Direction.NEXT : cursor.direction();
        if (direction == FiscalHistoryReadCursor.Direction.PREVIOUS) {
            java.util.Collections.reverse(visible);
        }
        boolean hasPrevious = direction == FiscalHistoryReadCursor.Direction.PREVIOUS
                ? hasExtra : cursor != null && !visible.isEmpty();
        boolean hasNext = direction == FiscalHistoryReadCursor.Direction.NEXT
                ? hasExtra : cursor != null && !visible.isEmpty();
        var previousCursor = hasPrevious ? FiscalHistoryReadCursorCodec.encode(
                new FiscalHistoryReadCursor(timestamp.apply(visible.getFirst()), id.apply(visible.getFirst()),
                        FiscalHistoryReadCursor.Direction.PREVIOUS, fingerprint)) : null;
        var nextCursor = hasNext ? FiscalHistoryReadCursorCodec.encode(
                new FiscalHistoryReadCursor(timestamp.apply(visible.getLast()), id.apply(visible.getLast()),
                        FiscalHistoryReadCursor.Direction.NEXT, fingerprint)) : null;
        return new FiscalHistoryReadCursorPage<>(visible, size, nextCursor, previousCursor,
                hasNext, hasPrevious);
    }

    private Scope scope() {
        var company = organization.currentCompany();
        if (company == null || company.getId() == null) {
            throw new IllegalStateException("La empresa actual no esta inicializada");
        }
        var installation = FiscalInstallationResolver.resolveCurrent(
                organization, installations, licenses);
        if (installation == null || installation.getId() == null) {
            throw new IllegalStateException("La instalacion fiscal no esta inicializada");
        }
        var store = organization.currentStore();
        if (store == null) {
            throw new IllegalStateException("La tienda actual no esta inicializada");
        }
        if (store.getEmpresa() != null && !company.getId().equals(store.getEmpresa().getId())) {
            throw new IllegalStateException("La tienda actual no pertenece a la empresa fiscal");
        }
        return new Scope(company.getId(), store.getId(), installation.getId());
    }

    private static int safeLimit(Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit debe estar entre 1 y " + MAX_LIMIT);
        }
        return limit;
    }

    private record Scope(UUID companyId, UUID storeId, UUID installationId) {
    }
}

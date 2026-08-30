package com.tpverp.saas.fiscal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FiscalStatusAdminService {
    private static final Duration STALE_AFTER = Duration.ofHours(24);
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private final SaasFiscalStatusRepository statuses;
    private final Clock clock;

    public FiscalStatusAdminService(
            SaasFiscalStatusRepository statuses,
            com.tpverp.saas.license.SaasInstallationRepository installations,
            com.tpverp.saas.license.SaasStoreRepository stores,
            Clock clock) {
        this.statuses = statuses;
        this.clock = clock;
    }

    /** Compatibility adapter; it cannot return more than the bounded first page. */
    @Transactional(readOnly = true)
    public List<FiscalStatusAdminView> all() {
        return page(null, null, null, null, null, null, null, 200).items();
    }

    /** Compatibility adapter; it cannot return more than the bounded first page. */
    @Transactional(readOnly = true)
    public List<FiscalStatusAdminView> company(UUID companyId) {
        return page(companyId, null, null, null, null, null, null, 200).items();
    }

    /** Compatibility adapter; company summaries are also bounded. */
    @Transactional(readOnly = true)
    public List<FiscalCompanyStatusAdminView> companies() {
        return companyPage(null, null, null, 200).items();
    }

    @Transactional(readOnly = true)
    public FiscalStatusAdminPage<FiscalStatusAdminView> page(
            UUID companyId, UUID storeId, UUID installationId, String effectiveMode,
            String activationState, Boolean stale, String cursor, int requestedSize) {
        int size = pageSize(requestedSize);
        FiscalStatusAdminCursor after = FiscalStatusAdminCursor.decode(cursor);
        List<SaasFiscalStatusRepository.AdminStatusRow> rows = statuses.findAdminPage(
                companyId, storeId, installationId, effectiveMode, activationState, stale,
                clock.instant().minus(STALE_AFTER),
                after == null ? null : after.companySort(), after == null ? null : after.storeSort(),
                after == null ? null : after.codeSort(), after == null ? null : after.storeId(),
                PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        List<FiscalStatusAdminView> result = rows.stream().limit(size).map(this::view).toList();
        String next = hasMore && !result.isEmpty() ? cursor(rows.get(size - 1)) : null;
        return new FiscalStatusAdminPage<>(result, next, hasMore, result.size());
    }

    @Transactional(readOnly = true)
    public FiscalStatusAdminPage<FiscalCompanyStatusAdminView> companyPage(
            UUID companyId, String companyName, String cursor, int requestedSize) {
        int size = pageSize(requestedSize);
        FiscalCompanyCursor after = FiscalCompanyCursor.decode(cursor);
        List<SaasFiscalStatusRepository.AdminCompanyRow> rows = statuses.findAdminCompanyPage(
                companyId, like(companyName), clock.instant().minus(STALE_AFTER),
                after == null ? null : after.companySort(), after == null ? null : after.companyId(),
                PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        List<FiscalCompanyStatusAdminView> result = rows.stream().limit(size).map(this::companyView).toList();
        String next = hasMore && !result.isEmpty()
                ? new FiscalCompanyCursor(rows.get(size - 1).getCompanySort(), rows.get(size - 1).getCompanyId()).encode()
                : null;
        return new FiscalStatusAdminPage<>(result, next, hasMore, result.size());
    }

    private FiscalStatusAdminView view(SaasFiscalStatusRepository.AdminStatusRow row) {
        return new FiscalStatusAdminView(row.getCompanyId(), row.getCompanyName(), row.getTaxId(), row.getStoreId(),
                row.getStoreName(), row.getInstallationId(), row.getInstallationReference(), row.getEffectiveMode(),
                row.getActivationState(), row.getModeVersion(), row.getModeSince(), row.getActivationDate(),
                row.getPolicyVersion(), row.getRuntimeClass(), row.getEndpointEnvironment(), row.getTransportMode(),
                row.getReportedAt(), row.getReceivedAt(), row.isStale());
    }

    private FiscalCompanyStatusAdminView companyView(SaasFiscalStatusRepository.AdminCompanyRow row) {
        String mode = row.getModeCount() == 1 ? row.getSingleMode() : "MIXED";
        String state = row.getStateCount() == 0 ? "UNKNOWN"
                : row.getStateCount() == 1 ? row.getSingleState() : "MIXED";
        return new FiscalCompanyStatusAdminView(row.getCompanyId(), row.getCompanyName(), row.getTaxId(), mode, state,
                Math.toIntExact(row.getStores()), Math.toIntExact(row.getInstallations()),
                Math.toIntExact(row.getUnlinkedStores()), Math.toIntExact(row.getStaleInstallations()),
                row.getLastReportedAt());
    }

    private String cursor(SaasFiscalStatusRepository.AdminStatusRow row) {
        return new FiscalStatusAdminCursor(row.getCompanySort(), row.getStoreSort(), row.getCodeSort(),
                row.getStoreId()).encode();
    }

    private String like(String companyName) {
        return companyName == null || companyName.isBlank() ? null : "%" + companyName.trim() + "%";
    }

    private int pageSize(int requestedSize) {
        if (requestedSize <= 0) return DEFAULT_PAGE_SIZE;
        if (requestedSize > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size no puede superar 200");
        }
        return requestedSize;
    }
}

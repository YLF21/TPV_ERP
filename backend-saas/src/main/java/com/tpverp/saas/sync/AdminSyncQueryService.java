package com.tpverp.saas.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminSyncQueryService {
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final SaasSyncEventRepository events;
    private final ObjectMapper mapper;

    public AdminSyncQueryService(SaasSyncEventRepository events, ObjectMapper mapper) {
        this.events = events;
        this.mapper = mapper;
    }

    /** Compatibility adapter; legacy responses are deliberately bounded. */
    @Transactional(readOnly = true)
    public List<AdminSyncEventView> events(UUID companyId, UUID storeId) {
        return page(null, companyId, storeId, null, 200).items();
    }

    @Transactional(readOnly = true)
    public AdminSyncProjectionStatusView projectionStatus(UUID companyId, UUID storeId) {
        var counts = new EnumMap<SaasSyncEvent.ProjectionStatus, Long>(SaasSyncEvent.ProjectionStatus.class);
        Instant oldestReceivedAt = null;
        for (SaasSyncEventRepository.ProjectionStatusCount row : events.countProjectionStatuses(companyId, storeId)) {
            counts.put(row.getStatus(), row.getTotal());
            if (row.getStatus() == SaasSyncEvent.ProjectionStatus.RECEIVED) {
                oldestReceivedAt = row.getOldestReceivedAt();
            }
        }
        return new AdminSyncProjectionStatusView(
                counts.getOrDefault(SaasSyncEvent.ProjectionStatus.RECEIVED, 0L),
                counts.getOrDefault(SaasSyncEvent.ProjectionStatus.PROJECTED, 0L),
                counts.getOrDefault(SaasSyncEvent.ProjectionStatus.IGNORED, 0L),
                counts.getOrDefault(SaasSyncEvent.ProjectionStatus.ERROR, 0L), oldestReceivedAt);
    }

    /** Compatibility adapter; legacy responses are deliberately bounded. */
    @Transactional(readOnly = true)
    public List<AdminSyncEventView> sales(UUID companyId, UUID storeId) {
        return page("DOCUMENTO", companyId, storeId, null, 200).items();
    }

    @Transactional(readOnly = true)
    public AdminSalesSummaryView salesSummary(UUID companyId, UUID storeId) {
        SaasSyncEventRepository.SalesSummaryRow row = events.aggregateSales(companyId, storeId);
        int count = Math.toIntExact(row.getDocumentCount());
        BigDecimal total = row.getTotal() == null ? BigDecimal.ZERO : row.getTotal();
        return new AdminSalesSummaryView(count, total.stripTrailingZeros().toPlainString());
    }

    /** Compatibility adapter; legacy responses are deliberately bounded. */
    @Transactional(readOnly = true)
    public List<AdminSyncEventView> stockMovements(UUID companyId, UUID storeId) {
        return page("STOCK_MOVEMENT", companyId, storeId, null, 200).items();
    }

    /** Compatibility adapter; aggregation is performed in SQL and is bounded. */
    @Transactional(readOnly = true)
    public List<AdminStockSnapshotView> stockCurrent(UUID companyId, UUID storeId) {
        return stockPage(companyId, storeId, null, 200).items();
    }

    /** Compatibility adapter; legacy responses are deliberately bounded. */
    @Transactional(readOnly = true)
    public List<AdminSyncEventView> cashClosures(UUID companyId, UUID storeId) {
        return page("CIERRE_CAJA", companyId, storeId, null, 200).items();
    }

    @Transactional(readOnly = true)
    public AdminSyncPage<AdminSyncEventView> page(String entityType, UUID companyId, UUID storeId,
            String cursor, int requestedSize) {
        int size = pageSize(requestedSize);
        AdminSyncCursor after = AdminSyncCursor.decode(cursor);
        List<SaasSyncEvent> rows = after == null
                ? events.findFirstPage(entityType, companyId, storeId, PageRequest.of(0, size + 1))
                : events.findPageAfter(entityType, companyId, storeId, after.receivedAt(), after.eventId(),
                        PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        List<AdminSyncEventView> result = rows.stream().limit(size).map(this::view).toList();
        String next = hasMore && !result.isEmpty()
                ? new AdminSyncCursor(result.get(result.size() - 1).receivedAt(),
                        result.get(result.size() - 1).eventId()).encode() : null;
        return new AdminSyncPage<>(result, next, hasMore, result.size());
    }

    @Transactional(readOnly = true)
    public AdminSyncPage<AdminStockSnapshotView> stockPage(UUID companyId, UUID storeId,
            String cursor, int requestedSize) {
        int size = pageSize(requestedSize);
        String[] after = decodeStockCursor(cursor);
        List<SaasSyncEventRepository.StockSnapshotRow> rows = events.aggregateStock(companyId, storeId,
                after == null ? null : UUID.fromString(after[0]), after == null ? null : UUID.fromString(after[1]),
                after == null ? null : after[2], after == null ? null : after[3], size + 1);
        boolean hasMore = rows.size() > size;
        List<AdminStockSnapshotView> result = rows.stream().limit(size)
                .map(row -> new AdminStockSnapshotView(row.getCompanyId(), row.getStoreId(), row.getProductId(),
                        row.getWarehouseId(), row.getQuantity().stripTrailingZeros().toPlainString())).toList();
        String next = hasMore && !result.isEmpty() ? encodeStockCursor(result.get(result.size() - 1)) : null;
        return new AdminSyncPage<>(result, next, hasMore, result.size());
    }

    private int pageSize(int requestedSize) {
        if (requestedSize <= 0) return DEFAULT_PAGE_SIZE;
        if (requestedSize > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size no puede superar 200");
        }
        return requestedSize;
    }

    private AdminSyncEventView view(SaasSyncEvent event) {
        return new AdminSyncEventView(event.getEventId(), event.getCompany().getId(),
                event.getStore() == null ? null : event.getStore().getId(),
                event.getInstallation() == null ? null : event.getInstallation().getId(),
                event.getEntityType(), event.getEntityId(), event.getOperation(), event.getProjectionStatus(),
                event.getProjectedAt(), event.getProjectionError(), event.getSchemaVersion(),
                event.getReceivedAt(), payload(event.getPayload()));
    }

    private Map<String, Object> payload(String value) {
        try { return mapper.readValue(value, MAP_TYPE); }
        catch (Exception exception) { throw new IllegalStateException("No se pudo leer payload sync", exception); }
    }

    private String encodeStockCursor(AdminStockSnapshotView row) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ((row.companyId() == null ? "00000000-0000-0000-0000-000000000000" : row.companyId()) + "|"
                        + (row.storeId() == null ? "00000000-0000-0000-0000-000000000000" : row.storeId()) + "|"
                        + row.productId() + "|" + row.warehouseId())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String[] decodeStockCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String value = new String(java.util.Base64.getUrlDecoder().decode(cursor),
                    java.nio.charset.StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length != 4 || parts[0].isBlank() || parts[1].isBlank()
                    || parts[2].isBlank() || parts[3].isBlank()) throw new IllegalArgumentException();
            UUID.fromString(parts[0]);
            UUID.fromString(parts[1]);
            return parts;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cursor stock invalido");
        }
    }
}

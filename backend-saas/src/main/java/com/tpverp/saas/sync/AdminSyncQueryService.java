package com.tpverp.saas.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Comparator;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminSyncQueryService {

    private static final PageRequest RECENT_LIMIT = PageRequest.of(0, 200);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final SaasSyncEventRepository events;
    private final ObjectMapper mapper;

    public AdminSyncQueryService(SaasSyncEventRepository events, ObjectMapper mapper) {
        this.events = events;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<AdminSyncEventView> events(UUID companyId, UUID storeId) {
        return views(events.findRecent(null, companyId, storeId, RECENT_LIMIT));
    }

    @Transactional(readOnly = true)
    public AdminSyncProjectionStatusView projectionStatus(UUID companyId, UUID storeId) {
        var counts = new EnumMap<SaasSyncEvent.ProjectionStatus, Long>(
                SaasSyncEvent.ProjectionStatus.class);
        Instant oldestReceivedAt = null;
        for (SaasSyncEventRepository.ProjectionStatusCount row
                : events.countProjectionStatuses(companyId, storeId)) {
            counts.put(row.getStatus(), row.getTotal());
            if (row.getStatus() == SaasSyncEvent.ProjectionStatus.RECEIVED) {
                oldestReceivedAt = row.getOldestReceivedAt();
            }
        }
        return new AdminSyncProjectionStatusView(
                counts.getOrDefault(SaasSyncEvent.ProjectionStatus.RECEIVED, 0L),
                counts.getOrDefault(SaasSyncEvent.ProjectionStatus.PROJECTED, 0L),
                counts.getOrDefault(SaasSyncEvent.ProjectionStatus.IGNORED, 0L),
                counts.getOrDefault(SaasSyncEvent.ProjectionStatus.ERROR, 0L),
                oldestReceivedAt);
    }

    @Transactional(readOnly = true)
    public List<AdminSyncEventView> sales(UUID companyId, UUID storeId) {
        return views(events.findRecent("DOCUMENTO", companyId, storeId, RECENT_LIMIT));
    }

    @Transactional(readOnly = true)
    public AdminSalesSummaryView salesSummary(UUID companyId, UUID storeId) {
        int count = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (SaasSyncEvent event : events.findChronological("DOCUMENTO", companyId, storeId)) {
            if (event.getOperation() == SyncOperation.ANULAR) {
                continue;
            }
            Map<String, Object> payload = payload(event.getPayload());
            if (!isSaleDocument(String.valueOf(payload.get("tipo")))) {
                continue;
            }
            count++;
            total = total.add(decimal(payload.get("total")));
        }
        return new AdminSalesSummaryView(count, total.stripTrailingZeros().toPlainString());
    }

    @Transactional(readOnly = true)
    public List<AdminSyncEventView> stockMovements(UUID companyId, UUID storeId) {
        return views(events.findRecent("STOCK_MOVEMENT", companyId, storeId, RECENT_LIMIT));
    }

    @Transactional(readOnly = true)
    public List<AdminStockSnapshotView> stockCurrent(UUID companyId, UUID storeId) {
        Map<StockKey, BigDecimal> snapshot = new LinkedHashMap<>();
        for (SaasSyncEvent event : events.findChronological("STOCK_MOVEMENT", companyId, storeId)) {
            Map<String, Object> payload = payload(event.getPayload());
            var key = new StockKey(
                    event.getCompany().getId(),
                    event.getStore() == null ? null : event.getStore().getId(),
                    String.valueOf(payload.get("productoId")),
                    String.valueOf(payload.get("almacenId")));
            snapshot.merge(key, decimal(payload.get("cantidad")), BigDecimal::add);
        }
        return snapshot.entrySet().stream()
                .map(entry -> new AdminStockSnapshotView(
                        entry.getKey().companyId(),
                        entry.getKey().storeId(),
                        entry.getKey().productId(),
                        entry.getKey().warehouseId(),
                        entry.getValue().stripTrailingZeros().toPlainString()))
                .sorted(Comparator
                        .comparing(AdminStockSnapshotView::productId)
                        .thenComparing(AdminStockSnapshotView::warehouseId))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminSyncEventView> cashClosures(UUID companyId, UUID storeId) {
        return views(events.findRecent("CIERRE_CAJA", companyId, storeId, RECENT_LIMIT));
    }

    private List<AdminSyncEventView> views(List<SaasSyncEvent> values) {
        return values.stream().map(this::view).toList();
    }

    private AdminSyncEventView view(SaasSyncEvent event) {
        return new AdminSyncEventView(
                event.getEventId(),
                event.getCompany().getId(),
                event.getStore() == null ? null : event.getStore().getId(),
                event.getInstallation() == null ? null : event.getInstallation().getId(),
                event.getEntityType(),
                event.getEntityId(),
                event.getOperation(),
                event.getProjectionStatus(),
                event.getProjectedAt(),
                event.getProjectionError(),
                event.getSchemaVersion(),
                event.getReceivedAt(),
                payload(event.getPayload()));
    }

    private Map<String, Object> payload(String value) {
        try {
            return mapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo leer payload sync", exception);
        }
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return new BigDecimal(String.valueOf(value));
    }

    private boolean isSaleDocument(String type) {
        return "TICKET".equals(type) || type.endsWith("_VENTA");
    }

    private record StockKey(UUID companyId, UUID storeId, String productId, String warehouseId) {
    }
}

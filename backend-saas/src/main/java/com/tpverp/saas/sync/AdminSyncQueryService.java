package com.tpverp.saas.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminSyncQueryService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final SaasSyncEventRepository events;
    private final ObjectMapper mapper;

    public AdminSyncQueryService(SaasSyncEventRepository events, ObjectMapper mapper) {
        this.events = events;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<AdminSyncEventView> events() {
        return views(events.findTop200ByOrderByReceivedAtDesc());
    }

    @Transactional(readOnly = true)
    public List<AdminSyncEventView> sales() {
        return views(events.findTop200ByEntityTypeOrderByReceivedAtDesc("DOCUMENTO"));
    }

    @Transactional(readOnly = true)
    public List<AdminSyncEventView> stockMovements() {
        return views(events.findTop200ByEntityTypeOrderByReceivedAtDesc("STOCK_MOVEMENT"));
    }

    @Transactional(readOnly = true)
    public List<AdminStockSnapshotView> stockCurrent() {
        Map<StockKey, BigDecimal> snapshot = new LinkedHashMap<>();
        for (SaasSyncEvent event : events.findByEntityTypeOrderByReceivedAtAsc("STOCK_MOVEMENT")) {
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
    public List<AdminSyncEventView> cashClosures() {
        return views(events.findTop200ByEntityTypeOrderByReceivedAtDesc("CIERRE_CAJA"));
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

    private record StockKey(UUID companyId, UUID storeId, String productId, String warehouseId) {
    }
}

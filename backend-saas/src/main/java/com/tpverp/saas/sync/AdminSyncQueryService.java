package com.tpverp.saas.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
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
}

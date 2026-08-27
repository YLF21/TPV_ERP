package com.tpverp.backend.sync;

import java.time.Instant;
import java.util.UUID;

public record SyncOutboxIncidentView(
        UUID eventId,
        UUID companyId,
        UUID storeId,
        UUID terminalId,
        String entityType,
        UUID entityId,
        SyncOperation operation,
        SyncOutboxStatus status,
        int attempts,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    static SyncOutboxIncidentView from(SyncOutboxEvent event) {
        return new SyncOutboxIncidentView(
                event.getEventId(),
                event.getCompanyId(),
                event.getStoreId(),
                event.getTerminalId(),
                event.getEntityType(),
                event.getEntityId(),
                event.getOperation(),
                event.getStatus(),
                event.getAttempts(),
                event.getLastError(),
                event.getCreatedAt(),
                event.getUpdatedAt(),
                event.getVersion());
    }
}

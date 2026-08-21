package com.tpverp.backend.sync;

import java.util.Map;
import java.util.UUID;

public record SyncOutboundEventCommand(
        UUID companyId,
        UUID storeId,
        UUID terminalId,
        Long storeSequence,
        String entityType,
        UUID entityId,
        SyncOperation operation,
        Map<String, Object> payload) {
    public SyncOutboundEventCommand(
            UUID companyId,
            UUID storeId,
            UUID terminalId,
            String entityType,
            UUID entityId,
            SyncOperation operation,
            Map<String, Object> payload) {
        this(companyId, storeId, terminalId, null,
                entityType, entityId, operation, payload);
    }
}

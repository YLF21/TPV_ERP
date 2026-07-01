package com.tpverp.backend.sync;

import java.util.Map;
import java.util.UUID;

public record SyncOutboundEventCommand(
        UUID companyId,
        UUID storeId,
        UUID terminalId,
        String entityType,
        UUID entityId,
        SyncOperation operation,
        Map<String, Object> payload) {
}

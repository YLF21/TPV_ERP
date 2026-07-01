package com.tpverp.saas.sync;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AdminSyncEventView(
        UUID eventId,
        UUID companyId,
        UUID storeId,
        UUID installationId,
        String entityType,
        UUID entityId,
        SyncOperation operation,
        Instant receivedAt,
        Map<String, Object> payload) {
}

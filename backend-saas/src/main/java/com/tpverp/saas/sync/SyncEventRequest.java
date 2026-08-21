package com.tpverp.saas.sync;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

public record SyncEventRequest(
        @NotNull UUID eventId,
        @NotNull UUID companyId,
        UUID storeId,
        Long storeSequence,
        UUID terminalId,
        @NotBlank String entityType,
        @NotNull UUID entityId,
        @NotNull SyncOperation operation,
        @NotNull Map<String, Object> payload) {

    public SyncEventRequest(
            UUID eventId,
            UUID companyId,
            UUID storeId,
            UUID terminalId,
            String entityType,
            UUID entityId,
            SyncOperation operation,
            Map<String, Object> payload) {
        this(eventId, companyId, storeId, null, terminalId, entityType, entityId, operation, payload);
    }
}

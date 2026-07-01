package com.tpverp.backend.sync;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

public record SyncInboundEventRequest(
        @NotNull UUID eventId,
        @NotNull UUID companyId,
        UUID storeId,
        @NotBlank String entityType,
        @NotNull UUID entityId,
        @NotNull SyncOperation operation,
        @NotNull Map<String, Object> payload) {
}

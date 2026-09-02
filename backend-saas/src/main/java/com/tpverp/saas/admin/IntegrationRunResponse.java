package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record IntegrationRunResponse(
        UUID id,
        UUID integrationId,
        String idempotencyKey,
        int attempt,
        String status,
        String deliveryMode,
        String payload,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant completedAt) {
}

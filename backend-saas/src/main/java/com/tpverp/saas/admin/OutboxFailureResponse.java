package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record OutboxFailureResponse(
        UUID id,
        String channel,
        String subject,
        int attempts,
        String error,
        Instant failedAt) {
}

package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record AdminAuditLogResponse(
        UUID id,
        String username,
        String action,
        String targetType,
        String targetId,
        String details,
        Instant createdAt) {
}

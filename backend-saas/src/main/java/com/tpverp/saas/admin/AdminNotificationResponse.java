package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record AdminNotificationResponse(
        String id,
        UUID companyId,
        String companyName,
        String severity,
        String title,
        String detail,
        Instant createdAt,
        boolean read) {

    public AdminNotificationResponse(
            String id,
            UUID companyId,
            String companyName,
            String severity,
            String title,
            String detail,
            Instant createdAt) {
        this(id, companyId, companyName, severity, title, detail, createdAt, false);
    }
}

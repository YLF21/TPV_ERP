package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record SupportTicketResponse(
        UUID id,
        UUID companyId,
        String companyName,
        String title,
        String description,
        String status,
        String priority,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {
}

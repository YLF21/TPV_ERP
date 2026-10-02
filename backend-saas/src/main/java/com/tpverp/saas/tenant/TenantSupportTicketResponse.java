package com.tpverp.saas.tenant;

import java.time.Instant;
import java.util.UUID;

/** Deliberately excludes technical identifiers, assignees and internal intervention evidence. */
public record TenantSupportTicketResponse(UUID id, UUID companyId, String companyName, String title,
        String description, String status, String priority, Instant createdAt, Instant updatedAt,
        String interventionStatus, Instant nextReviewAt, Instant visitAt) { }

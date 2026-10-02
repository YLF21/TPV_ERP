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
        Instant updatedAt,
        Long interventionVersion, String interventionStatus, UUID assigneeUserId, String assignee, Instant visitAt,
        Instant nextReviewAt, String failureKey, String failureStatus, Instant failureReceivedAt) {
    public SupportTicketResponse(UUID id, UUID companyId, String companyName, String title, String description,
            String status, String priority, String createdBy, Instant createdAt, Instant updatedAt, Long interventionVersion) {
        this(id,companyId,companyName,title,description,status,priority,createdBy,createdAt,updatedAt,interventionVersion,null,null,null,null,null,null,null,null);
    }
    public SupportTicketResponse(UUID id, UUID companyId, String companyName, String title, String description,
            String status, String priority, String createdBy, Instant createdAt, Instant updatedAt) {
        this(id, companyId, companyName, title, description, status, priority, createdBy, createdAt, updatedAt, null);
    }
}

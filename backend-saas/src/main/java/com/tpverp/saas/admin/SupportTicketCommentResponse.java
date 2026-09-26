package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record SupportTicketCommentResponse(UUID id, UUID ticketId, String author, String message,
        Instant createdAt, UUID requestId) {
    public SupportTicketCommentResponse(UUID id, UUID ticketId, String author, String message, Instant createdAt) {
        this(id, ticketId, author, message, createdAt, null);
    }
}

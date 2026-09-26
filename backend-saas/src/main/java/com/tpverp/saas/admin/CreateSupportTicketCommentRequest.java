package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateSupportTicketCommentRequest(
        @NotBlank @Size(max = 4000) String message,
        UUID requestId) {
    public CreateSupportTicketCommentRequest(String message) { this(message, null); }
}

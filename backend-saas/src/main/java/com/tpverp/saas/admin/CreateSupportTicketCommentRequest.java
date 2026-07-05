package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSupportTicketCommentRequest(
        @NotBlank @Size(max = 4000) String message) {
}

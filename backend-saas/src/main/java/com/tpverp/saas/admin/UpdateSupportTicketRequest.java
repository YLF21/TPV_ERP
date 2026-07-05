package com.tpverp.saas.admin;

import jakarta.validation.constraints.Size;

public record UpdateSupportTicketRequest(
        @Size(max = 32) String status,
        @Size(max = 32) String priority) {
}

package com.tpverp.saas.admin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateSupportTicketRequest(
        @Size(max = 32) String status,
        @Size(max = 32) String priority,
        @Min(0) Long expectedInterventionVersion,
        @Size(max = 32) String expectedTicketStatus) {
    public UpdateSupportTicketRequest(String status, String priority) { this(status, priority, null, null); }
}
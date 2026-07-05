package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateBillingInvoiceRequest(
        @NotBlank @Size(max = 80) String number,
        @NotBlank @Size(max = 240) String concept,
        @NotBlank @Size(max = 32) String amount,
        @NotBlank @Size(max = 8) String currency,
        @NotNull Instant issuedAt,
        @NotNull Instant dueAt) {
}

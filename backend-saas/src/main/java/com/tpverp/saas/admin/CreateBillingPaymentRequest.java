package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateBillingPaymentRequest(
        @NotBlank @Size(max = 32) String amount,
        @NotBlank @Size(max = 80) String method,
        @NotNull Instant paidAt,
        @Size(max = 120) String reference) {
}

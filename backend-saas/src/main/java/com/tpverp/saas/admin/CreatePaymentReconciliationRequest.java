package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record CreatePaymentReconciliationRequest(
        UUID paymentId,
        @NotBlank String provider,
        @NotBlank @Size(max = 160) String externalReference,
        @NotBlank String amount,
        @NotBlank String currency,
        @NotNull Instant bookedAt,
        @Size(max = 500) String notes) {
}

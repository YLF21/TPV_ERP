package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record CreateSubscriptionRequest(
        @NotBlank String planName,
        String status,
        @NotBlank String billingCycle,
        @NotBlank String amount,
        @NotBlank String currency,
        @NotNull Instant startedAt,
        Instant nextBillingAt) {
}

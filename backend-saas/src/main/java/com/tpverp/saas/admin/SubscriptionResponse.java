package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionResponse(
        UUID id,
        UUID companyId,
        String companyName,
        String planName,
        String status,
        String billingCycle,
        String amount,
        String currency,
        Instant startedAt,
        Instant nextBillingAt,
        Instant cancelledAt,
        Instant createdAt) {
}

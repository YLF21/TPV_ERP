package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record PaymentReconciliationResponse(
        UUID id,
        UUID companyId,
        UUID paymentId,
        String provider,
        String externalReference,
        String amount,
        String currency,
        Instant bookedAt,
        String status,
        String notes,
        Instant createdAt) {
}

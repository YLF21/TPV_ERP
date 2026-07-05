package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record BillingPaymentResponse(
        UUID id,
        UUID invoiceId,
        String amount,
        String method,
        String reference,
        Instant paidAt,
        Instant createdAt) {
}

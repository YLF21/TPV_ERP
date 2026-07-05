package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record BillingInvoiceResponse(
        UUID id,
        UUID companyId,
        String companyName,
        String number,
        String concept,
        String amount,
        String paidAmount,
        String currency,
        String status,
        Instant issuedAt,
        Instant dueAt,
        Instant createdAt) {
}

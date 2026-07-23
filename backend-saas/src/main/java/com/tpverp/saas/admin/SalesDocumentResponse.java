package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record SalesDocumentResponse(
        UUID id,
        UUID companyId,
        UUID storeId,
        String documentNumber,
        String customerCode,
        String total,
        String currency,
        String status,
        Instant issuedAt,
        Instant createdAt) {
}

package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record ErpProductResponse(
        UUID id,
        UUID companyId,
        String sku,
        String name,
        String category,
        String price,
        String taxRate,
        String minStock,
        boolean active,
        Instant createdAt) {
}

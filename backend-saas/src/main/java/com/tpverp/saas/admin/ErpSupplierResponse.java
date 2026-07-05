package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record ErpSupplierResponse(
        UUID id,
        UUID companyId,
        String code,
        String name,
        String taxId,
        String email,
        String phone,
        boolean active,
        Instant createdAt) {
}

package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record ErpWarehouseResponse(
        UUID id,
        UUID companyId,
        String code,
        String name,
        String address,
        boolean active,
        Instant createdAt) {
}

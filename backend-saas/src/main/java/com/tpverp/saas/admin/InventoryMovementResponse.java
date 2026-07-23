package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record InventoryMovementResponse(
        UUID id,
        UUID companyId,
        String warehouseCode,
        String productSku,
        String movementType,
        String quantity,
        String reason,
        Instant movedAt,
        Instant createdAt) {
}

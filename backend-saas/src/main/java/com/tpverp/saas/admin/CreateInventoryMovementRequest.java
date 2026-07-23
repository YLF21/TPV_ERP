package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record CreateInventoryMovementRequest(
        @NotBlank String warehouseCode,
        @NotBlank String productSku,
        @NotBlank String movementType,
        @NotBlank String quantity,
        String reason,
        @NotNull Instant movedAt) {
}

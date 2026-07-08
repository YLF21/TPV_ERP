package com.tpverp.backend.inventory;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record WarehouseInputLineCommand(
        @NotNull UUID productId,
        @Positive int quantity) {
}

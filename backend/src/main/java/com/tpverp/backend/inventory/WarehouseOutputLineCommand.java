package com.tpverp.backend.inventory;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record WarehouseOutputLineCommand(
        @NotNull UUID productId,
        @Positive int quantity) {
}

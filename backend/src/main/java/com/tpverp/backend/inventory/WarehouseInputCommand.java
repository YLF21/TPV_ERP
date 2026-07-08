package com.tpverp.backend.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record WarehouseInputCommand(
        @NotNull UUID warehouseId,
        @NotNull LocalDate date,
        UUID supplierId,
        String origin,
        String concept,
        @NotEmpty List<@Valid WarehouseInputLineCommand> lines) {
}

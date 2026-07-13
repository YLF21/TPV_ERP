package com.tpverp.backend.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record StockMinimumCommand(
        @NotNull @DecimalMin("0.000") @Digits(integer = 16, fraction = 3)
        BigDecimal minimumStock) {
}

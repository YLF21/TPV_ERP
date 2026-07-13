package com.tpverp.backend.inventory;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StockColumnSetting(
        @NotBlank @Size(max = 64) String key,
        @Min(72) @Max(420) int width) {
}

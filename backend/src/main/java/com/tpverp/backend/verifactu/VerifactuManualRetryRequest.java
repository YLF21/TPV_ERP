package com.tpverp.backend.verifactu;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record VerifactuManualRetryRequest(
        @NotBlank @Size(max = 500) String reason,
        @NotNull @PositiveOrZero Long expectedVersion) {
}

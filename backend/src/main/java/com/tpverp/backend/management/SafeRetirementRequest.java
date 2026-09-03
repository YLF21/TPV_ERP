package com.tpverp.backend.management;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record SafeRetirementRequest(
        @NotNull @PositiveOrZero Long expectedVersion) {
}

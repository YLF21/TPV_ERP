package com.tpverp.saas.admin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record RenewLicenseRequest(
        @NotNull Instant validUntil,
        @Min(value = 1, message = "maxWindows debe ser al menos 1") int maxWindows,
        @Min(value = 0, message = "maxPda no puede ser negativo") int maxPda) {
}

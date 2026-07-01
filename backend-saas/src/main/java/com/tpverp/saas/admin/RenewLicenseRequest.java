package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record RenewLicenseRequest(
        @NotNull Instant validUntil,
        int maxWindows,
        int maxPda) {
}

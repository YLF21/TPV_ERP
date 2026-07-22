package com.tpverp.backend.verifactu;

import java.time.Instant;

public record VerifactuAdminClockSummary(
        boolean available,
        boolean warning,
        String warningCode,
        Long driftSeconds,
        Long thresholdSeconds,
        Instant checkedAt) {
}

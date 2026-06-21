package com.tpverp.backend.verifactu;

import java.time.Instant;

public record VerifactuClockStatusView(
        boolean warning,
        String warningCode,
        Instant applicationTime,
        Instant databaseTime,
        long driftSeconds,
        long thresholdSeconds,
        Instant checkedAt) {
}

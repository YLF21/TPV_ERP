package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.time.LocalDate;

public record FiscalScheduledTransitionView(
        FiscalMode previousMode,
        FiscalMode newMode,
        FiscalModeTransitionStatus status,
        Instant requestedAt,
        Instant effectiveAt,
        LocalDate verifactuEndDate,
        String aeatAckReference,
        String lastErrorCode) {
}

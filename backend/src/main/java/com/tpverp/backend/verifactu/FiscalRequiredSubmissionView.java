package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.UUID;

public record FiscalRequiredSubmissionView(
        UUID id, String reference, String status, Instant requestedAt,
        Instant attendedAt, UUID exportId) {
}

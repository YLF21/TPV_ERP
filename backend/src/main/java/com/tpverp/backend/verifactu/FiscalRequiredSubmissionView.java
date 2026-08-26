package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

public record FiscalRequiredSubmissionView(
        UUID id, String reference, String status, Instant requestedAt,
        Instant attendedAt, UUID exportId, OffsetDateTime periodStart,
        OffsetDateTime periodEnd) {
    public FiscalRequiredSubmissionView(UUID id, String reference, String status,
            Instant requestedAt, Instant attendedAt, UUID exportId) {
        this(id, reference, status, requestedAt, attendedAt, exportId, null, null);
    }
}

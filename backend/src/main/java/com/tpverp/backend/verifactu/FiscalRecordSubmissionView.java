package com.tpverp.backend.verifactu;

import java.time.Instant;

/** Optional submission state; technical response/error bodies are intentionally omitted. */
public record FiscalRecordSubmissionView(
        FiscalSubmissionStatus status,
        Instant updatedAt,
        String errorCode) {
}

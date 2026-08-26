package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.UUID;

/** Immutable boundary metadata used to build an auditable export manifest. */
public record FiscalExportRecordView(
        UUID recordId,
        long sequence,
        String number,
        Instant generatedAt,
        String hash) {
}

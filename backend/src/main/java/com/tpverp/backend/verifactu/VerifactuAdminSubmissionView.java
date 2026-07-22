package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.UUID;

public record VerifactuAdminSubmissionView(
        UUID recordId,
        long sequence,
        String documentNumber,
        FiscalDocumentType documentType,
        FiscalRecordOperation operation,
        FiscalSubmissionStatus status,
        Instant updatedAt,
        String errorCode) {
}

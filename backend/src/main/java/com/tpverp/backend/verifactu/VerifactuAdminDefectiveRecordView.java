package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record VerifactuAdminDefectiveRecordView(
        UUID recordId,
        long sequence,
        String documentNumber,
        FiscalDocumentType documentType,
        FiscalRecordOperation operation,
        LocalDate issueDate,
        FiscalSubmissionStatus status,
        Instant updatedAt,
        String errorCode) {
}

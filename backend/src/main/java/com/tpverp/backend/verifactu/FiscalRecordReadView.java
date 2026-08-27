package com.tpverp.backend.verifactu;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Sanitized list projection. It deliberately has no snapshot or XML payload. */
public record FiscalRecordReadView(
        UUID recordId,
        UUID installationId,
        UUID storeId,
        UUID documentId,
        long sequence,
        FiscalRecordOperation operation,
        FiscalDocumentType documentType,
        String number,
        LocalDate issueDate,
        Instant generatedAt,
        FiscalMode fiscalMode,
        BigDecimal totalTax,
        BigDecimal totalAmount,
        String previousHash,
        String hash,
        FiscalSubmissionStatus submissionStatus,
        Instant submissionUpdatedAt) {

    static FiscalRecordReadView from(FiscalRecordReadRepository.Row row) {
        return new FiscalRecordReadView(
                row.recordId(), row.installationId(), row.storeId(), row.documentId(),
                row.sequence(), row.operation(), row.documentType(), row.number(),
                row.issueDate(), row.generatedAt(), row.fiscalMode(), row.totalTax(),
                row.totalAmount(), row.previousHash(), row.hash(), row.submissionStatus(),
                row.submissionUpdatedAt());
    }
}

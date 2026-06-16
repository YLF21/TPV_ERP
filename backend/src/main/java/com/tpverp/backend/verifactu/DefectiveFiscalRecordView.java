package com.tpverp.backend.verifactu;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DefectiveFiscalRecordView(
        UUID recordId,
        UUID documentId,
        FiscalSubmissionStatus status,
        FiscalRecordOperation operation,
        FiscalDocumentType documentType,
        String number,
        LocalDate issueDate,
        Instant generatedAt,
        BigDecimal totalAmount,
        String errorCode,
        String error,
        Instant updatedAt) {

    public static DefectiveFiscalRecordView from(
            FiscalRecord record, FiscalSubmissionState state) {
        return new DefectiveFiscalRecordView(
                record.getId(), record.getDocumentId(), state.getStatus(),
                record.getOperation(), record.getDocumentType(), record.getNumber(),
                record.getIssueDate(), record.getGeneratedAt(), record.getTotalAmount(),
                state.getLastErrorCode(), state.getLastError(), state.getUpdatedAt());
    }
}

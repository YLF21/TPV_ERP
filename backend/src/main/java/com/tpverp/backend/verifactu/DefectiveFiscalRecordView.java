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
        String qrUrl,
        String errorCode,
        String error,
        Instant updatedAt) {

    public static DefectiveFiscalRecordView from(
            FiscalRecord record, FiscalSubmissionState state) {
        return from(record, state, (FiscalQrUrlService) null);
    }

    public static DefectiveFiscalRecordView from(
            FiscalRecord record, FiscalSubmissionState state, FiscalQrUrlService qrUrls) {
        return from(record, state, qrUrl(record, qrUrls));
    }

    public static DefectiveFiscalRecordView from(
            FiscalRecord record, FiscalSubmissionState state, String frozenQrUrl) {
        return new DefectiveFiscalRecordView(
                record.getId(), record.getDocumentId(), state.getStatus(),
                record.getOperation(), record.getDocumentType(), record.getNumber(),
                record.getIssueDate(), record.getGeneratedAt(), record.getTotalAmount(),
                frozenQrUrl, state.getLastErrorCode(), state.getLastError(),
                state.getUpdatedAt());
    }

    static String qrUrl(FiscalRecord record, FiscalQrUrlService qrUrls) {
        if (qrUrls == null || record.getTotalAmount() == null) {
            return null;
        }
        return qrUrls.productionUrl(record);
    }
}

package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.UUID;

public record FiscalSubmissionQueueItem(
        UUID recordId,
        long sequence,
        FiscalSubmissionStatus status,
        FiscalRecordOperation operation,
        FiscalDocumentType documentType,
        String number,
        String errorCode,
        String error,
        Instant updatedAt) {

    public static FiscalSubmissionQueueItem from(
            FiscalRecord record, FiscalSubmissionState state) {
        return new FiscalSubmissionQueueItem(
                record.getId(), record.getSequence(), state.getStatus(),
                record.getOperation(), record.getDocumentType(), record.getNumber(),
                state.getLastErrorCode(), state.getLastError(), state.getUpdatedAt());
    }
}

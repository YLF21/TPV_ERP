package com.tpverp.backend.verifactu;

import java.util.UUID;

public record FiscalSubmissionQueueItem(
        UUID recordId,
        long sequence,
        FiscalSubmissionStatus status,
        FiscalRecordOperation operation,
        FiscalDocumentType documentType,
        String number) {

    public static FiscalSubmissionQueueItem from(
            FiscalRecord record, FiscalSubmissionState state) {
        return new FiscalSubmissionQueueItem(
                record.getId(), record.getSequence(), state.getStatus(),
                record.getOperation(), record.getDocumentType(), record.getNumber());
    }
}

package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.UUID;

public record FiscalSubmissionAttemptView(
        UUID id,
        UUID recordId,
        Instant attemptedAt,
        FiscalSubmissionStatus status,
        String errorCode,
        String error,
        String requestXml,
        String responsePayload) {

    public static FiscalSubmissionAttemptView from(FiscalSubmissionAttempt attempt) {
        return new FiscalSubmissionAttemptView(
                attempt.getId(), attempt.getRecordId(), attempt.getAttemptedAt(),
                attempt.getStatus(), attempt.getErrorCode(), attempt.getError(),
                attempt.getRequestXml(), attempt.getResponsePayload());
    }
}

package com.tpverp.backend.verifactu;

public record VerifactuSubmissionResult(
        FiscalSubmissionStatus status,
        String errorCode,
        String error,
        String responsePayload) {
}

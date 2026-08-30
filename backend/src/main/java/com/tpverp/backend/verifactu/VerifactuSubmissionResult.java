package com.tpverp.backend.verifactu;

public record VerifactuSubmissionResult(
        FiscalSubmissionStatus status,
        String errorCode,
        String error,
        String responsePayload,
        boolean networkRequestIssued) {

    public VerifactuSubmissionResult(
            FiscalSubmissionStatus status,
            String errorCode,
            String error,
            String responsePayload) {
        this(status, errorCode, error, responsePayload, false);
    }
}

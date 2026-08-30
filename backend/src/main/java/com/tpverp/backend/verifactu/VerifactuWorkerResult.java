package com.tpverp.backend.verifactu;

import java.util.UUID;

public record VerifactuWorkerResult(
        boolean processed,
        FiscalSubmissionStatus status,
        String errorCode,
        String error,
        UUID recordId,
        boolean networkRequestIssued) {

    public VerifactuWorkerResult(
            boolean processed,
            FiscalSubmissionStatus status,
            String errorCode,
            String error) {
        this(processed, status, errorCode, error, null, false);
    }

    public VerifactuWorkerResult(
            boolean processed,
            FiscalSubmissionStatus status,
            String errorCode,
            String error,
            UUID recordId) {
        this(processed, status, errorCode, error, recordId, false);
    }

    public static VerifactuWorkerResult empty() {
        return new VerifactuWorkerResult(false, null, null, null, null, false);
    }

    public static VerifactuWorkerResult from(VerifactuSubmissionResult result) {
        return new VerifactuWorkerResult(
                true, result.status(), result.errorCode(), result.error(), null,
                result.networkRequestIssued());
    }

    public static VerifactuWorkerResult from(
            UUID recordId, VerifactuSubmissionResult result) {
        return new VerifactuWorkerResult(
                true, result.status(), result.errorCode(), result.error(), recordId,
                result.networkRequestIssued());
    }

    public static VerifactuWorkerResult from(VerifactuBatchSubmissionResult result) {
        var first = result.results().isEmpty() ? null : result.results().getFirst();
        return new VerifactuWorkerResult(
                result.processed(),
                first == null ? result.globalStatus() : first.status(),
                first == null ? result.errorCode() : first.errorCode(),
                first == null ? result.error() : first.error(),
                null,
                result.networkRequestIssued());
    }
}

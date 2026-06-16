package com.tpverp.backend.verifactu;

public record VerifactuWorkerResult(
        boolean processed,
        FiscalSubmissionStatus status,
        String errorCode,
        String error) {

    public static VerifactuWorkerResult empty() {
        return new VerifactuWorkerResult(false, null, null, null);
    }

    public static VerifactuWorkerResult from(VerifactuSubmissionResult result) {
        return new VerifactuWorkerResult(
                true, result.status(), result.errorCode(), result.error());
    }
}

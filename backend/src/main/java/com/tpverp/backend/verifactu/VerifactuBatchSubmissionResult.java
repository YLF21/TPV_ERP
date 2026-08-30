package com.tpverp.backend.verifactu;

import java.util.List;

public record VerifactuBatchSubmissionResult(
        boolean processed,
        FiscalSubmissionStatus globalStatus,
        List<VerifactuSubmissionResult> results,
        Integer waitSeconds,
        boolean networkRequestIssued,
        String errorCode,
        String error) {
    public VerifactuBatchSubmissionResult {
        results = results == null ? List.of() : List.copyOf(results);
    }
}

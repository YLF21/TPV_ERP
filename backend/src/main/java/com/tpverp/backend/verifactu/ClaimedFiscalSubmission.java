package com.tpverp.backend.verifactu;

public record ClaimedFiscalSubmission(
        FiscalRecord record,
        FiscalSubmissionState state) {
}

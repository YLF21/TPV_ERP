package com.tpverp.backend.verifactu;

public record FiscalRequiredSubmissionExportView(
        FiscalRequiredSubmissionView requirement,
        FiscalExportView export) {
}

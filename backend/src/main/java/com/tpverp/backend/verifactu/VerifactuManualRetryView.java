package com.tpverp.backend.verifactu;

import java.util.UUID;

public record VerifactuManualRetryView(
        UUID recordId,
        FiscalSubmissionStatus status,
        String errorCode) {
}

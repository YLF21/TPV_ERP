package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.UUID;

public record VerifactuAdminAttemptView(
        UUID attemptId,
        Instant attemptedAt,
        FiscalSubmissionStatus status,
        String errorCode,
        boolean hasTechnicalDetail) {
}

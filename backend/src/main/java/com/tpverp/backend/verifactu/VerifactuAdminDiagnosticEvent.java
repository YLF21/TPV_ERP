package com.tpverp.backend.verifactu;

import java.time.Instant;

public record VerifactuAdminDiagnosticEvent(
        Instant occurredAt,
        FiscalSubmissionStatus status) {
}

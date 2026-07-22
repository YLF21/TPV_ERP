package com.tpverp.backend.verifactu;

import java.time.Instant;

public record VerifactuAdminDiagnosticView(
        boolean endpointConfigured,
        VerifactuEndpointMode endpointMode,
        boolean workerEnabled,
        VerifactuAdminClockSummary clock,
        VerifactuAdminDiagnosticEvent lastAttempt,
        Instant observedAt) {
}

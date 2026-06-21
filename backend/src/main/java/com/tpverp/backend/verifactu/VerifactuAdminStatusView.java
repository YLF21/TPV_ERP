package com.tpverp.backend.verifactu;

import java.time.Instant;

public record VerifactuAdminStatusView(
        boolean certificateConfigured,
        boolean certificateValid,
        String warning,
        String certificateSubject,
        Instant certificateNotBefore,
        Instant certificateNotAfter,
        VerifactuEndpointMode endpointMode,
        boolean workerEnabled,
        boolean signatureRequired,
        String signatureMode,
        boolean verifactuActive,
        String activationMode,
        Instant effectiveActivationAt,
        Instant firstSubmissionAt) {
}

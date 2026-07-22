package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.Map;

public record VerifactuAdminSummaryView(
        boolean active,
        String activationMode,
        Instant effectiveActivationAt,
        Instant firstSubmissionAt,
        VerifactuEndpointMode endpointMode,
        boolean workerEnabled,
        Map<FiscalSubmissionStatus, Long> countsByStatus,
        Instant oldestPendingAt,
        VerifactuAdminCertificateSummary certificate,
        VerifactuAdminClockSummary clock) {

    public VerifactuAdminSummaryView {
        countsByStatus = Map.copyOf(countsByStatus);
    }
}

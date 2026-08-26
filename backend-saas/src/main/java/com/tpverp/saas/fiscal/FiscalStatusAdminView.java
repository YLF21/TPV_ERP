package com.tpverp.saas.fiscal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FiscalStatusAdminView(
        UUID companyId,
        String companyName,
        String taxId,
        UUID storeId,
        String storeName,
        UUID installationId,
        String installationReference,
        String effectiveMode,
        String activationState,
        long modeVersion,
        Instant modeSince,
        LocalDate activationDate,
        Long policyVersion,
        String runtimeClass,
        String endpointEnvironment,
        String transportMode,
        Instant reportedAt,
        Instant receivedAt,
        boolean stale) {
}

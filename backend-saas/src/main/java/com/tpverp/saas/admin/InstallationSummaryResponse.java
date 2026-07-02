package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record InstallationSummaryResponse(
        UUID installationId,
        String installationReference,
        UUID companyId,
        UUID storeId,
        String licenseReference,
        Instant linkedAt,
        Instant lastValidatedAt) {
}

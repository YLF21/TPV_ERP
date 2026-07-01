package com.tpverp.saas.admin;

import com.tpverp.saas.license.LicenseSaasStatus;
import java.time.Instant;
import java.util.UUID;

public record LicenseSummaryResponse(
        String licenseReference,
        UUID companyId,
        String companyName,
        String taxId,
        LicenseSaasStatus status,
        Instant validUntil,
        int maxWindows,
        int maxPda) {
}

package com.tpverp.saas.admin;

import com.tpverp.saas.license.LicenseSaasStatus;
import java.time.Instant;

public record AdminLicenseResponse(
        String licenseReference,
        LicenseSaasStatus status,
        Instant validUntil) {
}

package com.tpverp.saas.license;

import java.time.Instant;

public record LicenseSaasValidationResponse(
        LicenseSaasStatus status,
        Instant validUntil) {
}

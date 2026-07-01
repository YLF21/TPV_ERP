package com.tpverp.backend.licensing;

import java.time.Instant;

public record LicenseSaasValidationResponse(
        LicenseSaasStatus status,
        Instant validUntil) {
}

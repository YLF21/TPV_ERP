package com.tpverp.saas.license;

import java.time.Instant;
import java.time.LocalDate;

public record LicenseSaasValidationResponse(
        LicenseSaasStatus status,
        Instant validUntil,
        LocalDate verifactuActivationDate,
        long verifactuPolicyVersion,
        Instant verifactuPolicyUpdatedAt) {
}

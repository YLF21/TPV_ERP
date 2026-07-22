package com.tpverp.backend.licensing;

import java.time.Instant;
import java.time.LocalDate;

public record LicenseSaasValidationResponse(
        LicenseSaasStatus status,
        Instant validUntil,
        LocalDate verifactuActivationDate,
        long verifactuPolicyVersion,
        Instant verifactuPolicyUpdatedAt) {

    public LicenseSaasValidationResponse(LicenseSaasStatus status, Instant validUntil) {
        this(status, validUntil, null, 0, null);
    }
}

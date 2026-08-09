package com.tpverp.backend.licensing;

import com.tpverp.backend.licensing.application.CommercialProfile;
import java.time.Instant;
import java.time.LocalDate;

public record LicenseSaasValidationResponse(
        LicenseSaasStatus status,
        Instant validUntil,
        LocalDate verifactuActivationDate,
        long verifactuPolicyVersion,
        Instant verifactuPolicyUpdatedAt,
        CommercialProfile commercialProfile) {

    public LicenseSaasValidationResponse(
            LicenseSaasStatus status,
            Instant validUntil,
            LocalDate verifactuActivationDate,
            long verifactuPolicyVersion,
            Instant verifactuPolicyUpdatedAt) {
        this(status, validUntil, verifactuActivationDate, verifactuPolicyVersion,
                verifactuPolicyUpdatedAt, null);
    }

    public LicenseSaasValidationResponse(LicenseSaasStatus status, Instant validUntil) {
        this(status, validUntil, null, 0, null, null);
    }
}

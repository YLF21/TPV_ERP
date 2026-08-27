package com.tpverp.backend.licensing;

import com.tpverp.backend.licensing.application.CommercialProfile;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LicenseSaasValidationResponse(
        LicenseSaasStatus status,
        Instant validUntil,
        LocalDate verifactuActivationDate,
        long verifactuPolicyVersion,
        Instant verifactuPolicyUpdatedAt,
        CommercialProfile commercialProfile,
        int maxWindows,
        int maxPda,
        long licenseVersion,
        UUID saasCompanyId,
        UUID saasStoreId,
        String licenseReference,
        String taxId) {

    public LicenseSaasValidationResponse(
            LicenseSaasStatus status,
            Instant validUntil,
            LocalDate verifactuActivationDate,
            long verifactuPolicyVersion,
            Instant verifactuPolicyUpdatedAt,
            CommercialProfile commercialProfile,
            int maxWindows,
            int maxPda,
            long licenseVersion) {
        this(status, validUntil, verifactuActivationDate, verifactuPolicyVersion,
                verifactuPolicyUpdatedAt, commercialProfile, maxWindows, maxPda,
                licenseVersion, null, null, null, null);
    }

    public LicenseSaasValidationResponse(
            LicenseSaasStatus status,
            Instant validUntil,
            LocalDate verifactuActivationDate,
            long verifactuPolicyVersion,
            Instant verifactuPolicyUpdatedAt) {
        this(status, validUntil, verifactuActivationDate, verifactuPolicyVersion,
                verifactuPolicyUpdatedAt, null, 1, 0, 1, null, null, null, null);
    }

    public LicenseSaasValidationResponse(LicenseSaasStatus status, Instant validUntil) {
        this(status, validUntil, null, 0, null, null, 1, 0, 1,
                null, null, null, null);
    }
}

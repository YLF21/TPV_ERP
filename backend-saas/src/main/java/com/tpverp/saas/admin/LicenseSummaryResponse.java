package com.tpverp.saas.admin;

import com.tpverp.saas.license.CommercialProfile;
import com.tpverp.saas.license.LicenseSaasStatus;
import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.TaxpayerType;
import java.time.Instant;
import java.util.UUID;

public record LicenseSummaryResponse(
        String licenseReference,
        UUID companyId,
        String companyName,
        String taxId,
        TaxpayerType taxpayerType,
        TaxRegime taxRegime,
        CommercialProfile commercialProfile,
        LicenseSaasStatus status,
        Instant validUntil,
        int maxWindows,
        int maxPda) {
}

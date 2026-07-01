package com.tpverp.saas.license;

import java.time.Instant;
import java.util.UUID;

public record LicenseSaasLinkResponse(
        String licenseReference,
        UUID companyId,
        UUID storeId,
        Instant validUntil,
        LicenseSaasStatus status,
        int maxWindows,
        int maxPda,
        String taxId,
        TaxpayerType taxpayerType,
        TaxRegime impuestos,
        String installationToken) {
}

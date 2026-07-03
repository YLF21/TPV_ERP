package com.tpverp.backend.licensing;

import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.licensing.application.TaxpayerType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record LicenseSaasLinkResponse(
        String licenseReference,
        UUID companyId,
        UUID storeId,
        String companyTaxId,
        String companyName,
        Map<String, String> companyAddress,
        String storeCode,
        String storeName,
        Map<String, String> storeAddress,
        Instant validUntil,
        LicenseSaasStatus status,
        int maxWindows,
        int maxPda,
        String taxId,
        TaxpayerType taxpayerType,
        TaxRegime impuestos,
        String installationToken) {
}

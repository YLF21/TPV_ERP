package com.tpverp.saas.license;

import java.time.Instant;
import java.time.LocalDate;
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
        String timeZoneId,
        Instant validUntil,
        LicenseSaasStatus status,
        int maxWindows,
        int maxPda,
        long licenseVersion,
        String taxId,
        TaxpayerType taxpayerType,
        TaxRegime impuestos,
        CommercialProfile commercialProfile,
        LocalDate verifactuActivationDate,
        long verifactuPolicyVersion,
        Instant verifactuPolicyUpdatedAt,
        String installationToken) {

    public LicenseSaasLinkResponse(String licenseReference, UUID companyId, UUID storeId,
            Instant validUntil, LicenseSaasStatus status, int maxWindows, int maxPda,
            String taxId, TaxpayerType taxpayerType, TaxRegime impuestos,
            LocalDate verifactuActivationDate, long verifactuPolicyVersion,
            Instant verifactuPolicyUpdatedAt, String installationToken) {
        this(licenseReference, companyId, storeId, taxId, null, null, null, null, null,
                null, validUntil, status, maxWindows, maxPda, 1,
                taxId, taxpayerType, impuestos, null,
                verifactuActivationDate, verifactuPolicyVersion,
                verifactuPolicyUpdatedAt, installationToken);
    }
}

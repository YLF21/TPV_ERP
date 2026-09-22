package com.tpverp.saas;

import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.TaxpayerType;
import com.tpverp.saas.license.CommercialProfile;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import com.tpverp.saas.admin.CompanyOwner;

public record ProvisioningRequest(
        @NotBlank String name,
        @NotBlank String taxId,
        @NotNull TaxpayerType taxpayerType,
        @NotNull TaxRegime impuestos,
        @NotNull CommercialProfile commercialProfile,
        @NotNull Map<String, String> companyAddress,
        @NotBlank String storeCode,
        String storeName,
        @NotNull Map<String, String> storeAddress,
        @NotBlank String timeZoneId,
        @NotNull Instant validUntil,
        @Min(value = 1, message = "maxWindows debe ser al menos 1") int maxWindows,
        @Min(value = 0, message = "maxPda no puede ser negativo") int maxPda,
        List<CompanyOwner> owners) {
    public ProvisioningRequest(String name, String taxId, TaxpayerType taxpayerType,
            TaxRegime impuestos, CommercialProfile commercialProfile, Map<String, String> companyAddress,
            String storeCode, String storeName, Map<String, String> storeAddress, String timeZoneId,
            Instant validUntil, int maxWindows, int maxPda) {
        this(name, taxId, taxpayerType, impuestos, commercialProfile, companyAddress,
                storeCode, storeName, storeAddress, timeZoneId, validUntil, maxWindows, maxPda,
                SaasTestData.companyOwners());
    }
    public ProvisioningRequest(String name, String taxId, TaxpayerType taxpayerType,
            TaxRegime impuestos, String storeCode, String storeName, Instant validUntil,
            int maxWindows, int maxPda) {
        this(name, taxId, taxpayerType, impuestos, CommercialProfile.MAYORISTA,
                null, storeCode, storeName, null, null, validUntil, maxWindows, maxPda);
    }
}

package com.tpverp.saas.license;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

public record LicenseSaasLinkRequest(
        @NotBlank @Size(max = 64) String pairingCode,
        @NotNull UUID installationId,
        @NotBlank String installationReference,
        String installationPublicKey,
        UUID storeId,
        String storeCode,
        String taxId,
        String companyName,
        Map<String, String> companyAddress,
        Map<String, String> storeAddress,
        String timeZoneId) {

    public LicenseSaasLinkRequest(
            String pairingCode,
            UUID installationId,
            String installationReference,
            String installationPublicKey,
            UUID storeId,
            String storeCode,
            String taxId,
            String companyName) {
        this(pairingCode, installationId, installationReference, installationPublicKey,
                storeId, storeCode, taxId, companyName, null, null, null);
    }

    public LicenseSaasLinkRequest(
            String pairingCode,
            UUID installationId,
            String installationReference,
            String installationPublicKey,
            UUID storeId,
            String storeCode,
            String taxId,
            String companyName,
            Map<String, String> companyAddress,
            Map<String, String> storeAddress) {
        this(pairingCode, installationId, installationReference, installationPublicKey,
                storeId, storeCode, taxId, companyName, companyAddress, storeAddress, null);
    }
}

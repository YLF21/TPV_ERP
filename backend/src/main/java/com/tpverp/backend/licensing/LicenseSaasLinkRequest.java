package com.tpverp.backend.licensing;

import java.util.UUID;
import java.util.Map;

public record LicenseSaasLinkRequest(
        String pairingCode,
        UUID installationId,
        String installationReference,
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

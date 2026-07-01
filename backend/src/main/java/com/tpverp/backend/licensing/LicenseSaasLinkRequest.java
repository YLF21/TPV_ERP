package com.tpverp.backend.licensing;

import java.util.UUID;

public record LicenseSaasLinkRequest(
        String pairingCode,
        UUID installationId,
        String installationReference,
        String installationPublicKey,
        UUID storeId,
        String storeCode,
        String taxId,
        String companyName) {
}

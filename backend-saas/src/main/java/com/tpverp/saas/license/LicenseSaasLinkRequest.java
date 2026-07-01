package com.tpverp.saas.license;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record LicenseSaasLinkRequest(
        @NotBlank String pairingCode,
        @NotNull UUID installationId,
        @NotBlank String installationReference,
        String installationPublicKey,
        UUID storeId,
        String storeCode,
        String taxId,
        String companyName) {
}

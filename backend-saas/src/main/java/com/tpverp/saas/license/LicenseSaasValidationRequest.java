package com.tpverp.saas.license;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record LicenseSaasValidationRequest(
        @NotNull UUID installationId,
        @NotBlank String installationReference,
        UUID storeId,
        @NotBlank String licenseReference,
        String licenseHash) {
}

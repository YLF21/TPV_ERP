package com.tpverp.backend.licensing;

import java.util.UUID;

public record LicenseSaasValidationRequest(
        UUID installationId,
        String installationReference,
        UUID storeId,
        String licenseReference,
        String licenseHash) {
}

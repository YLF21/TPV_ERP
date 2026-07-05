package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record CreateCompanyResponse(
        UUID companyId,
        UUID storeId,
        String licenseReference,
        String pairingCode,
        Instant validUntil,
        String tenantUsername,
        String tenantInitialPassword) {
}

package com.tpverp.saas;

import java.time.Instant;
import java.util.UUID;

public record ProvisionedCompany(
        UUID companyId,
        UUID storeId,
        String licenseReference,
        String pairingCode,
        Instant validUntil,
        String tenantUsername,
        String tenantInitialPassword) {
}

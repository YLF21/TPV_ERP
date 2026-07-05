package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record BillingCompanyResponse(
        UUID companyId,
        String companyName,
        String taxId,
        String planName,
        String billingStatus,
        Instant renewalDate,
        String monthlyPrice,
        String licenseReference,
        Instant validUntil,
        boolean renewalDueSoon,
        boolean overdue) {
}

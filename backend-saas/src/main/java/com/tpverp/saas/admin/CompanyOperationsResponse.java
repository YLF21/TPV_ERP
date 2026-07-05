package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record CompanyOperationsResponse(
        UUID companyId,
        String planName,
        String billingStatus,
        Instant renewalDate,
        String monthlyPrice,
        String supportStatus,
        String contactName,
        String contactEmail,
        String notes) {
}

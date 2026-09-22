package com.tpverp.saas.tenant;

import java.time.Instant;
import java.util.UUID;

public record TenantDashboardResponse(
        UUID companyId,
        String companyName,
        Long licenses,
        long stores,
        long installations,
        Long openTickets,
        String billingStatus,
        Instant renewalDate,
        String monthlyPrice) {
}

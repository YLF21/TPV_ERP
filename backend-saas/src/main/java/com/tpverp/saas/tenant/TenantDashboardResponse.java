package com.tpverp.saas.tenant;

import java.time.Instant;
import java.util.UUID;

public record TenantDashboardResponse(
        UUID companyId,
        String companyName,
        long licenses,
        long stores,
        long installations,
        long openTickets,
        String billingStatus,
        Instant renewalDate,
        String monthlyPrice) {
}

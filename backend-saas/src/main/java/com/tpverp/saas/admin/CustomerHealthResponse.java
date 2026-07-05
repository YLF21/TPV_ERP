package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CustomerHealthResponse(
        UUID companyId,
        String companyName,
        String taxId,
        String planName,
        String billingStatus,
        String licenseStatus,
        Instant validUntil,
        long installations,
        long staleInstallations,
        Instant lastValidationAt,
        long eventsLast7Days,
        Instant lastEventAt,
        long openTickets,
        long urgentTickets,
        int score,
        String riskLevel,
        List<String> signals) {
}

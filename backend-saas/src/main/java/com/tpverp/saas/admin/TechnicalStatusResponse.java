package com.tpverp.saas.admin;

import java.time.Instant;

public record TechnicalStatusResponse(
        Instant generatedAt,
        long companies,
        long licenses,
        long installations,
        long eventsToday,
        long openTickets,
        long staleInstallations,
        Instant lastSyncAt) {
}

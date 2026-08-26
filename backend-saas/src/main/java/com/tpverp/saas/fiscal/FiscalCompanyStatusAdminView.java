package com.tpverp.saas.fiscal;

import java.time.Instant;
import java.util.UUID;

public record FiscalCompanyStatusAdminView(
        UUID companyId,
        String companyName,
        String taxId,
        String effectiveMode,
        String activationState,
        int stores,
        int installations,
        int unlinkedStores,
        int staleInstallations,
        Instant lastReportedAt) {
}

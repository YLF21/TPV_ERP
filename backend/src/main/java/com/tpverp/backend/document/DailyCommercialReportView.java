package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record DailyCommercialReportView(
        UUID storeId,
        LocalDate date,
        BigDecimal issuedTotal,
        BigDecimal collectedTotal,
        BigDecimal generatedPendingTotal,
        BigDecimal collectedPreviousPendingTotal) {
}

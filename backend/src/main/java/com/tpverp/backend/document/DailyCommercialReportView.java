package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record DailyCommercialReportView(
        UUID storeId,
        LocalDate date,
        BigDecimal invoiced,
        BigDecimal collectedCurrent,
        BigDecimal newPending,
        BigDecimal priorDebtCollected,
        BigDecimal cashInflow) {
}

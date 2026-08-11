package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DailyCommercialReportView(
        UUID storeId,
        LocalDate date,
        BigDecimal invoiced,
        BigDecimal ticketSales,
        BigDecimal collectedCurrent,
        BigDecimal newPending,
        BigDecimal priorDebtCollected,
        BigDecimal refunds,
        BigDecimal cashInflow,
        List<DailyCommercialReportDayView> days) {
}

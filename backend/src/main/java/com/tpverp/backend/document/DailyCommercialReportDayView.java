package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyCommercialReportDayView(
        LocalDate date,
        BigDecimal invoiced,
        BigDecimal ticketSales,
        BigDecimal collectedCurrent,
        BigDecimal newPending,
        BigDecimal priorDebtCollected,
        BigDecimal refunds,
        BigDecimal cashInflow,
        long ticketCount,
        long invoiceCount,
        BigDecimal salesTotal) {
}

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
        long ticketCount,
        long invoiceCount,
        BigDecimal salesTotal,
        DailyPaymentBreakdownView salesByPaymentMethod,
        DailyPaymentBreakdownView pendingCollectionsByPaymentMethod,
        DailyPaymentBreakdownView refundsByPaymentMethod,
        BigDecimal openingCashFund,
        BigDecimal cashEntries,
        BigDecimal cashWithdrawals,
        BigDecimal expectedCash,
        List<DailyCommercialReportDayView> days) {
}

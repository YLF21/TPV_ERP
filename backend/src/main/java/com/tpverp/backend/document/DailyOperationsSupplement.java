package com.tpverp.backend.document;

import java.math.BigDecimal;

/**
 * The non-sales part of the daily commercial report.
 *
 * <p>Sales totals and their payment methods stay on {@link SalesDailySummaryView};
 * this DTO deliberately contains only collections, refunds and cash so that every
 * presentation route has one narrow, redacted source.</p>
 */
public record DailyOperationsSupplement(
        BigDecimal collectedCurrent,
        BigDecimal newPending,
        BigDecimal priorDebtCollected,
        DailyPaymentBreakdownView pendingCollectionsByPaymentMethod,
        DailyPaymentBreakdownView refundsByPaymentMethod,
        BigDecimal cashInflow,
        BigDecimal openingCashFund,
        BigDecimal cashEntries,
        BigDecimal cashWithdrawals,
        BigDecimal expectedCash) {

    public static DailyOperationsSupplement from(DailyCommercialReportView report) {
        return new DailyOperationsSupplement(
                report.collectedCurrent(), report.newPending(), report.priorDebtCollected(),
                report.pendingCollectionsByPaymentMethod(), report.refundsByPaymentMethod(),
                report.cashInflow(), report.openingCashFund(), report.cashEntries(),
                report.cashWithdrawals(), report.expectedCash());
    }
}

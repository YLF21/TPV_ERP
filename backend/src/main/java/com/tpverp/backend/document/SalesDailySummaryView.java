package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SalesDailySummaryView(
        UUID storeId,
        String companyName,
        String storeCode,
        LocalDate date,
        BigDecimal netSalesTotal,
        List<PaymentTotalView> paymentMethods,
        ActivityCountsView counts,
        List<UserSummaryView> users,
        DailyOperationsSupplement operations,
        LocalDate currentDate) {

    public SalesDailySummaryView(
            UUID storeId,
            String companyName,
            String storeCode,
            LocalDate date,
            BigDecimal netSalesTotal,
            List<PaymentTotalView> paymentMethods,
            ActivityCountsView counts,
            List<UserSummaryView> users) {
        this(storeId, companyName, storeCode, date, netSalesTotal, paymentMethods, counts, users, null, null);
    }

    public record PaymentTotalView(
            SalesActivityPaymentMethod method,
            long operationCount,
            BigDecimal amount) {
    }

    public record ActivityCountsView(
            long sales,
            long returns,
            long cancelled,
            long pending) {
    }

    public record UserSummaryView(
            UUID userId,
            String userName,
            BigDecimal netSalesTotal,
            List<PaymentTotalView> paymentMethods,
            ActivityCountsView counts) {
    }
}

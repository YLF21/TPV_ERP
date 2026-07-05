package com.tpverp.saas.admin;

import java.util.List;

public record BillingSummaryResponse(
        long totalCompanies,
        long paidCompanies,
        long pendingCompanies,
        long overdueCompanies,
        long renewalsNext30Days,
        String monthlyRecurringRevenue,
        List<BillingCompanyResponse> companies) {
}

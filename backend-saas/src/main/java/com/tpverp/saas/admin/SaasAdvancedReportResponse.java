package com.tpverp.saas.admin;

public record SaasAdvancedReportResponse(
        long companies,
        long subscriptions,
        String subscriptionMrr,
        long invoices,
        String invoicedTotal,
        String paidTotal,
        long salesDocuments,
        String salesTotal,
        long inventoryMovements,
        long integrations,
        long activeIntegrations) {
}

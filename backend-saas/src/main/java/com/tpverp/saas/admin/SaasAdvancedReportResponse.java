package com.tpverp.saas.admin;

public record SaasAdvancedReportResponse(
        long companies,
        long invoices,
        String invoicedTotal,
        String paidTotal,
        long salesDocuments,
        String salesTotal,
        long inventoryMovements,
        long integrations,
        long activeIntegrations) {
}

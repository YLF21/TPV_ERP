package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SalesInvoiceRectificationSourceView(
        UUID id,
        CommercialDocumentType type,
        DocumentStatus status,
        String number,
        LocalDate issueDate,
        UUID customerId,
        UUID warehouseId,
        BigDecimal globalDiscount,
        BigDecimal base,
        BigDecimal tax,
        BigDecimal total,
        List<LineView> lines) {

    public record LineView(
            UUID id,
            DocumentLineType type,
            String code,
            String name,
            BigDecimal originalQuantity,
            BigDecimal availableStockQuantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            boolean taxesIncluded,
            String taxRegime,
            BigDecimal taxPercentage,
            BigDecimal base,
            BigDecimal tax,
            BigDecimal total) {
    }
}

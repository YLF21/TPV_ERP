package com.tpverp.backend.inventory;

import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StockSalesHistoryRow(
        UUID documentId,
        CommercialDocumentType documentType,
        String documentNumber,
        DocumentStatus status,
        Instant occurredAt,
        UUID customerId,
        String customerName,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal discountPercent,
        BigDecimal lineTotal,
        UUID userId,
        String userName,
        UUID storeId,
        String storeName,
        UUID warehouseId,
        String warehouseName) {
}

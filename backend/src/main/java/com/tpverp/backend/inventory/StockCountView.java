package com.tpverp.backend.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record StockCountView(
        UUID id, String number, UUID storeId, UUID warehouseId, StockCountStatus status, String notes,
        UUID createdBy, Instant createdAt, UUID confirmedBy, Instant confirmedAt,
        UUID cancelledBy, Instant cancelledAt, List<Line> lines, long version, LocalDate documentDate, String createdByName) {
    public record Line(
            UUID productId, String productCode, String productBarcode, String productName,
            BigDecimal expectedQuantity, BigDecimal countedQuantity,
            BigDecimal difference, BigDecimal appliedDifference) {}
}

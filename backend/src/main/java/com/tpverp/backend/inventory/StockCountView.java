package com.tpverp.backend.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StockCountView(
        UUID id, UUID storeId, UUID warehouseId, StockCountStatus status, String notes,
        UUID createdBy, Instant createdAt, UUID confirmedBy, Instant confirmedAt,
        UUID cancelledBy, Instant cancelledAt, List<Line> lines) {
    public record Line(
            UUID productId, String productCode, String productName,
            BigDecimal expectedQuantity, BigDecimal countedQuantity,
            BigDecimal difference, BigDecimal appliedDifference) {}
}

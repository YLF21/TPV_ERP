package com.tpverp.backend.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StockCountSummary(
        UUID id, UUID storeId, UUID warehouseId, StockCountStatus status, String notes,
        UUID createdBy, Instant createdAt, UUID confirmedBy, Instant confirmedAt,
        UUID cancelledBy, Instant cancelledAt, long lineCount, BigDecimal totalDifference) {}

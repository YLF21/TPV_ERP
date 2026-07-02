package com.tpverp.backend.inventory;

import java.util.UUID;
import java.math.BigDecimal;

public record StockSnapshotQuantity(UUID productId, UUID warehouseId, BigDecimal quantity) {
}

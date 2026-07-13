package com.tpverp.backend.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public record StockMinimumView(
        UUID productId,
        UUID warehouseId,
        BigDecimal minimumStock,
        boolean overridden) {
}

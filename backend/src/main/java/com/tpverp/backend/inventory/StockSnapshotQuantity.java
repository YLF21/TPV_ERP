package com.tpverp.backend.inventory;

import java.util.UUID;

public record StockSnapshotQuantity(UUID productId, UUID warehouseId, long quantity) {
}

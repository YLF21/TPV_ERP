package com.tpverp.backend.inventory;

import java.util.UUID;

public record WarehouseInputLineView(UUID productId, int quantity) {

    public static WarehouseInputLineView from(WarehouseInputLine line) {
        return new WarehouseInputLineView(line.getProductId(), line.getQuantity());
    }
}

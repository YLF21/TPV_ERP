package com.tpverp.backend.inventory;

import java.util.UUID;

public record WarehouseOutputLineView(UUID productId, int quantity) {

    public static WarehouseOutputLineView from(WarehouseOutputLine line) {
        return new WarehouseOutputLineView(line.getProductId(), line.getQuantity());
    }
}

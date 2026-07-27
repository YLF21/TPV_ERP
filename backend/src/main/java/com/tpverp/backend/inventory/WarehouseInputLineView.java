package com.tpverp.backend.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public record WarehouseInputLineView(
        UUID productId,
        int quantity,
        BigDecimal purchaseUnitPrice,
        BigDecimal purchaseTotal) {

    public static WarehouseInputLineView from(WarehouseInputLine line) {
        return new WarehouseInputLineView(
                line.getProductId(),
                line.getQuantity(),
                line.getPurchaseUnitPrice(),
                line.getPurchaseTotal());
    }
}

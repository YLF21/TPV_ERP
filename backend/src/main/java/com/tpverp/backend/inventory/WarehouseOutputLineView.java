package com.tpverp.backend.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public record WarehouseOutputLineView(
        UUID productId,
        int quantity,
        BigDecimal saleUnitPrice,
        BigDecimal saleTotal) {

    public static WarehouseOutputLineView from(WarehouseOutputLine line) {
        return new WarehouseOutputLineView(
                line.getProductId(),
                line.getQuantity(),
                line.getSaleUnitPrice(),
                line.getSaleTotal());
    }
}

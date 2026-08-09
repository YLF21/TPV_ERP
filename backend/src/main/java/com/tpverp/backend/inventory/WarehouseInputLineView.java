package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Product;
import java.math.BigDecimal;
import java.util.UUID;

public record WarehouseInputLineView(
        UUID productId,
        String productCode,
        String productName,
        int quantity,
        BigDecimal purchaseUnitPrice,
        BigDecimal purchaseTotal) {

    public static WarehouseInputLineView from(WarehouseInputLine line) {
        return from(line, null);
    }

    public static WarehouseInputLineView from(WarehouseInputLine line, Product product) {
        return new WarehouseInputLineView(
                line.getProductId(),
                product == null ? null : product.getCode(),
                product == null ? null : product.getName(),
                line.getQuantity(),
                line.getPurchaseUnitPrice(),
                line.getPurchaseTotal());
    }
}

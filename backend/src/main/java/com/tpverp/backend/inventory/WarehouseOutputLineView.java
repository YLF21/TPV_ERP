package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Product;
import java.math.BigDecimal;
import java.util.UUID;

public record WarehouseOutputLineView(
        UUID productId,
        String productCode,
        String productName,
        int quantity,
        BigDecimal saleUnitPrice,
        BigDecimal saleTotal,
        int position) {

    public static WarehouseOutputLineView from(WarehouseOutputLine line) {
        return from(line, null);
    }

    public static WarehouseOutputLineView from(WarehouseOutputLine line, Product product) {
        return new WarehouseOutputLineView(
                line.getProductId(),
                product == null ? null : product.getCode(),
                product == null ? null : product.getName(),
                line.getQuantity(),
                line.getSaleUnitPrice(),
                line.getSaleTotal(),
                line.getPosition());
    }
}

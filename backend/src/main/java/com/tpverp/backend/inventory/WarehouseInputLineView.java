package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Product;
import java.math.BigDecimal;
import java.util.UUID;

public record WarehouseInputLineView(
        UUID productId,
        String productCode,
        String productName,
        BigDecimal quantity,
        BigDecimal purchaseUnitPrice,
        BigDecimal discount,
        boolean priceOverridden,
        BigDecimal subtotal,
        BigDecimal purchaseTotal) {

    public WarehouseInputLineView(
            UUID productId,
            String productCode,
            String productName,
            int quantity,
            BigDecimal purchaseUnitPrice,
            BigDecimal purchaseTotal) {
        this(productId, productCode, productName, BigDecimal.valueOf(quantity), purchaseUnitPrice,
                BigDecimal.ZERO, false, purchaseTotal, purchaseTotal);
    }

    public static WarehouseInputLineView from(WarehouseInputLine line) {
        return from(line, null);
    }

    public static WarehouseInputLineView from(WarehouseInputLine line, Product product) {
        return new WarehouseInputLineView(
                line.getProductId(),
                product == null ? null : product.getCode(),
                line.getProductName(),
                line.getQuantity(),
                line.getPurchaseUnitPrice(),
                line.getDiscount(),
                line.isPriceOverridden(),
                line.getSubtotal(),
                line.getPurchaseTotal());
    }
}

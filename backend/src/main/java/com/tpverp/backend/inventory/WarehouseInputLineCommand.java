package com.tpverp.backend.inventory;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record WarehouseInputLineCommand(
        @NotNull UUID productId,
        @NotNull @Positive @Digits(integer = 16, fraction = 3) BigDecimal quantity,
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal unitPrice,
        @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2) BigDecimal discount,
        boolean priceOverridden,
        @Size(max = 255) String productName) {

    public WarehouseInputLineCommand(
            UUID productId,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            boolean priceOverridden) {
        this(productId, quantity, unitPrice, discount, priceOverridden, null);
    }

    public WarehouseInputLineCommand(UUID productId, int quantity) {
        this(productId, BigDecimal.valueOf(quantity), null, BigDecimal.ZERO, false, null);
    }

    public WarehouseInputLineCommand(UUID productId, BigDecimal quantity) {
        this(productId, quantity, null, BigDecimal.ZERO, false, null);
    }

    public WarehouseInputLineCommand valued(BigDecimal resolvedPrice) {
        return new WarehouseInputLineCommand(
                productId, quantity, priceOverridden ? unitPrice : resolvedPrice,
                discount == null ? BigDecimal.ZERO : discount, priceOverridden, productName);
    }

    public WarehouseInputLineCommand valued(BigDecimal resolvedPrice, String defaultProductName) {
        var resolvedName = productName == null || productName.isBlank()
                ? defaultProductName
                : productName.trim();
        return new WarehouseInputLineCommand(
                productId, quantity, priceOverridden ? unitPrice : resolvedPrice,
                discount == null ? BigDecimal.ZERO : discount, priceOverridden, resolvedName);
    }
}

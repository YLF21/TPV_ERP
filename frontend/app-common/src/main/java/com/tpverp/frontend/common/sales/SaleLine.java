package com.tpverp.frontend.common.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record SaleLine(
        String code,
        String name,
        BigDecimal quantity,
        BigDecimal packages,
        BigDecimal price,
        BigDecimal discountPercent,
        int unitsPerPackage
) {

    public SaleLine(ProductSnapshot product, BigDecimal discountPercent) {
        this(product.code(), product.name(), BigDecimal.ONE, BigDecimal.ZERO, product.salePrice(), discountPercent,
                product.unitsPerPackage());
    }

    public SaleLine {
        quantity = normalize(quantity);
        packages = normalize(packages);
        price = money(price);
        discountPercent = normalize(discountPercent);
        if (unitsPerPackage < 1) {
            unitsPerPackage = 1;
        }
    }

    public SaleLine withQuantity(BigDecimal quantity) {
        return new SaleLine(code, name, quantity, packages, price, discountPercent, unitsPerPackage);
    }

    public SaleLine withPackages(BigDecimal packages) {
        return new SaleLine(code, name, packages.multiply(BigDecimal.valueOf(unitsPerPackage)), packages, price,
                discountPercent, unitsPerPackage);
    }

    public SaleLine withDiscount(BigDecimal discountPercent) {
        return new SaleLine(code, name, quantity, packages, price, discountPercent, unitsPerPackage);
    }

    public SaleLine withPrice(BigDecimal price) {
        return new SaleLine(code, name, quantity, packages, price, discountPercent, unitsPerPackage);
    }

    public BigDecimal totalBeforeDiscount() {
        return money(price.multiply(quantity));
    }

    public BigDecimal totalAfterDiscount() {
        BigDecimal multiplier = BigDecimal.ONE.subtract(discountPercent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        return money(totalBeforeDiscount().multiply(multiplier));
    }

    private static BigDecimal normalize(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value.stripTrailingZeros().toPlainString());
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}

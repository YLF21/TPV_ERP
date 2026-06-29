package com.tpverp.frontend.common.sales;

import java.math.BigDecimal;

public record ProductSnapshot(String code, String name, BigDecimal salePrice, int unitsPerPackage) {

    public ProductSnapshot {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (salePrice == null || salePrice.signum() < 0) {
            throw new IllegalArgumentException("salePrice must be positive or zero");
        }
        if (unitsPerPackage < 1) {
            unitsPerPackage = 1;
        }
    }
}

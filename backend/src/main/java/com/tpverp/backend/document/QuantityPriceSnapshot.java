package com.tpverp.backend.document;

import com.tpverp.backend.catalog.QuantityPricing;
import java.math.BigDecimal;
import java.util.Objects;

/** Catalog conditions in force when a product line was priced. */
public record QuantityPriceSnapshot(QuantityPricing rule, BigDecimal salePrice) {
    public QuantityPriceSnapshot {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(salePrice, "salePrice");
        if (salePrice.signum() < 0) throw new IllegalArgumentException("salePrice");
    }
}

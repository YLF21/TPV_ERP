package com.tpverp.backend.catalog;

import java.math.BigDecimal;
import java.util.Objects;

/** Shared quantity policy for operations tied to a catalog product. */
public final class ProductQuantityPolicy {

    private ProductQuantityPolicy() {
    }

    public static void requireValid(ProductType productType, BigDecimal quantity) {
        Objects.requireNonNull(productType, "productType");
        Objects.requireNonNull(quantity, "quantity");
        if (quantity.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException("message.document.quantity_scale");
        }
        if (productType == ProductType.UNIT
                && quantity.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(
                    "message.product.unit_quantity_must_be_integer");
        }
    }
}

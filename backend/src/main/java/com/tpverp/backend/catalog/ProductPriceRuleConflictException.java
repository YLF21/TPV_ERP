package com.tpverp.backend.catalog;

import java.util.List;
import java.util.UUID;

public class ProductPriceRuleConflictException extends IllegalStateException {

    private final UUID productId;
    private final String productName;
    private final ProductPriceRulePreview.Field field;
    private final List<Integer> formIndexes;

    public ProductPriceRuleConflictException(
            UUID productId,
            String productName,
            ProductPriceRulePreview.Field field,
            List<Integer> formIndexes) {
        super("Conflicto de regla para el producto " + productId + " en el campo " + field);
        this.productId = productId;
        this.productName = productName;
        this.field = field;
        this.formIndexes = List.copyOf(formIndexes);
    }

    public UUID getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public ProductPriceRulePreview.Field getField() {
        return field;
    }

    public List<Integer> getFormIndexes() {
        return formIndexes;
    }
}

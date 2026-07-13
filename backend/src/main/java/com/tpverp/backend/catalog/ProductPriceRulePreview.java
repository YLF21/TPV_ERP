package com.tpverp.backend.catalog;

import java.util.List;
import java.util.UUID;

public record ProductPriceRulePreview(
        UUID ruleId,
        long ruleVersion,
        int matchedProducts,
        List<ProductChange> products) {

    public enum Field {
        SALE_PRICE,
        MEMBER_PRICE,
        WHOLESALE_PRICE,
        OFFER_PRICE,
        PRICE_USE_MODE,
        DISCOUNT_TYPE,
        OFFER_DISCOUNT_PERCENT,
        OFFER_ACTIVE,
        OFFER_FROM,
        OFFER_UNTIL
    }

    public record ProductChange(
            UUID productId,
            String productName,
            List<FieldChange> changes) {
    }

    public record FieldChange(
            Field field,
            Object before,
            Object after,
            List<Integer> formIndexes) {
    }
}

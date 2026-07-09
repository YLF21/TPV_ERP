package com.tpverp.backend.promotion;

import java.math.BigDecimal;
import java.util.List;

public record PromotionPreview(List<PromotionBenefit> appliedPromotions, BigDecimal discountTotal) {

    public PromotionPreview {
        appliedPromotions = List.copyOf(appliedPromotions == null ? List.of() : appliedPromotions);
        discountTotal = discountTotal == null ? BigDecimal.ZERO : discountTotal;
    }
}

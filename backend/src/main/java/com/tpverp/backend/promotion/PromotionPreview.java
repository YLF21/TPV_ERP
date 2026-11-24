package com.tpverp.backend.promotion;

import java.math.BigDecimal;
import java.util.List;

public record PromotionPreview(List<PromotionBenefit> appliedPromotions, BigDecimal discountTotal,
        @com.fasterxml.jackson.annotation.JsonIgnore int engineVersion) {

    public PromotionPreview(List<PromotionBenefit> appliedPromotions, BigDecimal discountTotal) {
        this(appliedPromotions, discountTotal, 2);
    }

    public PromotionPreview {
        appliedPromotions = List.copyOf(appliedPromotions == null ? List.of() : appliedPromotions);
        discountTotal = discountTotal == null ? BigDecimal.ZERO : discountTotal;
    }

    static PromotionPreview priced(List<PromotionBenefit> selected) {
        var total = selected.stream().map(PromotionBenefit::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PromotionPreview(selected, PromotionEngine.euros(total), PromotionEngine.CURRENT_VERSION);
    }

    public BigDecimal payableDiscountTotal() {
        return appliedPromotions.stream().map(PromotionBenefit::payableAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

package com.tpverp.backend.promotion;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

public record PromotionBenefit(
        UUID promotionId,
        String name,
        Set<Integer> affectedPositions,
        BigDecimal amount,
        boolean taxIncluded,
        String taxRegime,
        BigDecimal taxPercent) {

    public PromotionBenefit {
        affectedPositions = Set.copyOf(affectedPositions);
    }
}

package com.tpverp.backend.promotion;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

public record PromotionBenefit(
        UUID promotionId,
        UUID promotionVersionId,
        String name,
        Set<Integer> affectedPositions,
        BigDecimal amount,
        boolean taxIncluded,
        String taxRegime,
        BigDecimal taxPercent) {

    public PromotionBenefit(
            UUID promotionId,
            String name,
            Set<Integer> affectedPositions,
            BigDecimal amount,
            boolean taxIncluded,
            String taxRegime,
            BigDecimal taxPercent) {
        this(promotionId, promotionId, name, affectedPositions, amount,
                taxIncluded, taxRegime, taxPercent);
    }

    public PromotionBenefit {
        if (promotionId == null || promotionVersionId == null) {
            throw new IllegalArgumentException("promotionId y promotionVersionId son obligatorios");
        }
        affectedPositions = Set.copyOf(affectedPositions);
    }
}

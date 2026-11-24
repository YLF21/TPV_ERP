package com.tpverp.backend.promotion;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        BigDecimal taxPercent,
        boolean quantityPrice,
        QuantityAmounts quantityAmounts) {

    public record QuantityAmounts(BigDecimal base, BigDecimal tax, BigDecimal total) {
    }

    public PromotionBenefit(UUID promotionId, UUID promotionVersionId, String name,
            Set<Integer> affectedPositions, BigDecimal amount, boolean taxIncluded,
            String taxRegime, BigDecimal taxPercent, boolean quantityPrice) {
        this(promotionId, promotionVersionId, name, affectedPositions, amount, taxIncluded,
                taxRegime, taxPercent, quantityPrice, null);
    }

    public PromotionBenefit(UUID promotionId, UUID promotionVersionId, String name,
            Set<Integer> affectedPositions, BigDecimal amount, boolean taxIncluded,
            String taxRegime, BigDecimal taxPercent) {
        this(promotionId, promotionVersionId, name, affectedPositions, amount,
                taxIncluded, taxRegime, taxPercent, false);
    }

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
        if (!quantityPrice && (promotionId == null || promotionVersionId == null)) {
            throw new IllegalArgumentException("promotionId y promotionVersionId son obligatorios");
        }
        if (quantityPrice && (promotionId != null || promotionVersionId != null)) {
            throw new IllegalArgumentException("El precio por cantidad no referencia una promocion");
        }
        affectedPositions = Set.copyOf(affectedPositions);
    }

    public BigDecimal payableAmount() {
        if (quantityAmounts != null) return quantityAmounts.total();
        return (taxIncluded ? amount : amount.multiply(BigDecimal.ONE.add(taxPercent.movePointLeft(2))))
                .setScale(2, RoundingMode.HALF_UP);
    }
}

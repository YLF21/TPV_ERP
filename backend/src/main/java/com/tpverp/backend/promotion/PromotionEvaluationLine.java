package com.tpverp.backend.promotion;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record PromotionEvaluationLine(
        int position,
        UUID productId,
        UUID familyId,
        UUID subfamilyId,
        BigDecimal quantity,
        BigDecimal unitPrice,
        boolean taxIncluded,
        String taxRegime,
        BigDecimal taxPercent,
        boolean manualDiscount,
        boolean discountable,
        BigDecimal payableUnitAmount) {

    public PromotionEvaluationLine(
            int position,
            UUID productId,
            UUID familyId,
            UUID subfamilyId,
            BigDecimal quantity,
            BigDecimal unitPrice,
            boolean taxIncluded,
            String taxRegime,
            BigDecimal taxPercent,
            boolean manualDiscount,
            boolean discountable) {
        this(position, productId, familyId, subfamilyId, quantity, unitPrice,
                taxIncluded, taxRegime, taxPercent, manualDiscount, discountable,
                payableUnitAmount(unitPrice, taxIncluded, taxPercent));
    }

    public PromotionEvaluationLine {
        if (position < 1) {
            throw new IllegalArgumentException("position debe ser positivo");
        }
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(taxRegime, "taxRegime");
        Objects.requireNonNull(taxPercent, "taxPercent");
        Objects.requireNonNull(payableUnitAmount, "payableUnitAmount");
        if (quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException("quantity debe ser positiva con hasta 3 decimales");
        }
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice no puede ser negativo");
        }
        if (payableUnitAmount.signum() < 0) {
            throw new IllegalArgumentException("payableUnitAmount no puede ser negativo");
        }
    }

    private static BigDecimal payableUnitAmount(
            BigDecimal unitPrice,
            boolean taxIncluded,
            BigDecimal taxPercent) {
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(taxPercent, "taxPercent");
        if (taxIncluded) {
            return unitPrice;
        }
        return unitPrice.multiply(BigDecimal.ONE.add(taxPercent.movePointLeft(2)));
    }
}

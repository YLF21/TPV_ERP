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
        boolean discountable) {

    public PromotionEvaluationLine {
        if (position < 1) {
            throw new IllegalArgumentException("position debe ser positivo");
        }
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(taxRegime, "taxRegime");
        Objects.requireNonNull(taxPercent, "taxPercent");
    }
}

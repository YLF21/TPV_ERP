package com.tpverp.backend.promotion;

import java.util.List;
import java.util.Map;
import com.tpverp.backend.document.QuantityPriceSnapshot;

public record PromotionEvaluationRequest(
        List<PromotionEvaluationLine> lines,
        List<Promotion> promotions,
        List<PromotionTarget> targets,
        Map<Integer, QuantityPriceSnapshot> quantityPrices) {

    public PromotionEvaluationRequest(List<PromotionEvaluationLine> lines, List<Promotion> promotions,
            List<PromotionTarget> targets) {
        this(lines, promotions, targets, Map.of());
    }

    public PromotionEvaluationRequest(List<PromotionEvaluationLine> lines, List<Promotion> promotions) {
        this(lines, promotions, List.of());
    }

    public PromotionEvaluationRequest {
        requireNoNulls(lines, "lines");
        requireNoNulls(promotions, "promotions");
        requireNoNulls(targets, "targets");
        lines = List.copyOf(lines == null ? List.of() : lines);
        promotions = List.copyOf(promotions == null ? List.of() : promotions);
        targets = List.copyOf(targets == null ? List.of() : targets);
        quantityPrices = Map.copyOf(quantityPrices == null ? Map.of() : quantityPrices);
    }

    private static void requireNoNulls(List<?> values, String field) {
        if (values != null && values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(field + " no puede contener null");
        }
    }
}

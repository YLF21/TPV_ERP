package com.tpverp.backend.promotion;

import java.util.List;

public record PromotionEvaluationRequest(
        List<PromotionEvaluationLine> lines,
        List<Promotion> promotions,
        List<PromotionTarget> targets) {

    public PromotionEvaluationRequest(List<PromotionEvaluationLine> lines, List<Promotion> promotions) {
        this(lines, promotions, List.of());
    }

    public PromotionEvaluationRequest {
        lines = List.copyOf(lines == null ? List.of() : lines);
        promotions = List.copyOf(promotions == null ? List.of() : promotions);
        targets = List.copyOf(targets == null ? List.of() : targets);
    }
}

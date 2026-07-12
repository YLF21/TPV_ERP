package com.tpverp.backend.promotion;

import java.math.BigDecimal;
import java.util.List;

final class PromotionEligibility {

    private PromotionEligibility() {
    }

    static List<PromotionEvaluationLine> eligibleLines(
            Promotion promotion,
            List<PromotionEvaluationLine> lines,
            List<PromotionTarget> targets) {
        return lines.stream()
                .filter(PromotionEvaluationLine::discountable)
                .filter(line -> !line.manualDiscount())
                .filter(line -> matches(promotion, line, targets))
                .toList();
    }

    static BigDecimal subtotal(List<PromotionEvaluationLine> lines) {
        return lines.stream()
                .map(line -> line.unitPrice().multiply(line.quantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static BigDecimal payableSubtotal(List<PromotionEvaluationLine> lines) {
        return lines.stream()
                .map(line -> line.payableUnitAmount().multiply(line.quantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static BigDecimal quantity(List<PromotionEvaluationLine> lines) {
        return lines.stream()
                .map(PromotionEvaluationLine::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static boolean matches(
            Promotion promotion,
            PromotionEvaluationLine line,
            List<PromotionTarget> targets) {
        var promotionTargets = targets.stream()
                .filter(target -> target.promotionId().equals(promotion.id()))
                .toList();
        if (promotionTargets.isEmpty()) {
            return promotion.scope() == PromotionScope.SALE;
        }
        return promotionTargets.stream().anyMatch(target ->
                (target.type() == PromotionTargetType.PRODUCT && target.targetId().equals(line.productId()))
                        || (target.type() == PromotionTargetType.FAMILY
                        && target.targetId().equals(line.familyId()))
                        || (target.type() == PromotionTargetType.SUBFAMILY
                        && target.targetId().equals(line.subfamilyId())));
    }
}

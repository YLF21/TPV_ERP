package com.tpverp.backend.promotion;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.DocumentLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class PromotionEngine {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public PromotionPreview preview(PromotionEvaluationRequest request) {
        var candidates = new ArrayList<PromotionBenefit>();
        for (var promotion : request.promotions()) {
            candidates.addAll(candidatesFor(promotion, request));
        }

        candidates.sort(Comparator
                .comparing(PromotionBenefit::amount, Comparator.reverseOrder())
                .thenComparing(benefit -> benefit.name() == null ? "" : benefit.name()));

        var usedPositions = new HashSet<Integer>();
        var selected = new ArrayList<PromotionBenefit>();
        for (var candidate : candidates) {
            if (candidate.amount().signum() <= 0 || overlaps(usedPositions, candidate.affectedPositions())) {
                continue;
            }
            selected.add(candidate);
            usedPositions.addAll(candidate.affectedPositions());
        }

        var total = selected.stream()
                .map(PromotionBenefit::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PromotionPreview(selected, euros(total));
    }

    public List<DocumentLine> promotionLines(CommercialDocument document, PromotionPreview preview) {
        var result = new ArrayList<DocumentLine>();
        var position = document.getLineas().stream()
                .mapToInt(DocumentLine::getPosicion)
                .max()
                .orElse(0) + 1;
        for (var benefit : preview.appliedPromotions()) {
            result.add(DocumentLine.promotion(
                    document,
                    position++,
                    "PROMOCION " + benefit.name(),
                    benefit.amount().negate(),
                    benefit.taxIncluded(),
                    benefit.taxRegime(),
                    benefit.taxPercent(),
                    benefit.promotionId(),
                    null));
        }
        return result;
    }

    private static List<PromotionBenefit> candidatesFor(
            Promotion promotion,
            PromotionEvaluationRequest request) {
        var units = eligibleUnits(promotion, request);
        if (promotion.type() == PromotionType.BUY_X_PAY_Y) {
            return buyXPayYCandidates(promotion, units);
        }
        if (promotion.type() == PromotionType.SECOND_UNIT_PERCENT) {
            return secondUnitPercentCandidates(promotion, units);
        }
        return List.of();
    }

    private static List<PromotionBenefit> buyXPayYCandidates(Promotion promotion, List<Unit> units) {
        var buy = wholeQuantity(promotion.buyQuantity());
        var pay = wholeQuantity(promotion.payQuantity());
        if (buy <= 0 || pay < 0 || pay >= buy || units.size() < buy) {
            return List.of();
        }

        var sorted = units.stream()
                .sorted(Comparator.comparing(Unit::price).reversed())
                .toList();
        var result = new ArrayList<PromotionBenefit>();
        for (var start = 0; start + buy <= sorted.size(); start += buy) {
            var pack = sorted.subList(start, start + buy);
            var discounted = pack.stream()
                    .sorted(Comparator.comparing(Unit::price))
                    .limit(buy - pay)
                    .map(Unit::price)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            result.add(benefit(promotion, pack, discounted));
        }
        return result;
    }

    private static List<PromotionBenefit> secondUnitPercentCandidates(Promotion promotion, List<Unit> units) {
        if (promotion.discountPercent() == null || promotion.discountPercent().signum() <= 0 || units.size() < 2) {
            return List.of();
        }

        var sorted = units.stream()
                .sorted(Comparator.comparing(Unit::price).reversed())
                .toList();
        var result = new ArrayList<PromotionBenefit>();
        for (var start = 0; start + 2 <= sorted.size(); start += 2) {
            var pair = sorted.subList(start, start + 2);
            var cheaper = pair.stream().min(Comparator.comparing(Unit::price)).orElseThrow();
            var discount = cheaper.price().multiply(promotion.discountPercent()).divide(HUNDRED, 6, RoundingMode.HALF_UP);
            result.add(benefit(promotion, pair, discount));
        }
        return result;
    }

    private static PromotionBenefit benefit(Promotion promotion, List<Unit> units, BigDecimal amount) {
        var first = units.getFirst().line();
        var positions = new HashSet<Integer>();
        for (var unit : units) {
            positions.add(unit.line().position());
        }
        return new PromotionBenefit(
                promotion.id(),
                promotion.name(),
                positions,
                euros(amount),
                first.taxIncluded(),
                first.taxRegime(),
                first.taxPercent());
    }

    private static List<Unit> eligibleUnits(Promotion promotion, PromotionEvaluationRequest request) {
        var result = new ArrayList<Unit>();
        for (var line : request.lines()) {
            if (!line.discountable() || line.manualDiscount() || !matches(promotion, line, request.targets())) {
                continue;
            }
            var quantity = wholeQuantity(line.quantity());
            for (var index = 0; index < quantity; index++) {
                result.add(new Unit(line));
            }
        }
        return result;
    }

    private static boolean matches(
            Promotion promotion,
            PromotionEvaluationLine line,
            List<PromotionTarget> targets) {
        var promotionTargets = targets.stream()
                .filter(target -> target.promotionId().equals(promotion.id()))
                .toList();
        if (promotionTargets.isEmpty()) {
            return true;
        }
        return promotionTargets.stream().anyMatch(target ->
                (target.type() == PromotionTargetType.PRODUCT && target.targetId().equals(line.productId()))
                        || (target.type() == PromotionTargetType.FAMILY && target.targetId().equals(line.familyId()))
                        || (target.type() == PromotionTargetType.SUBFAMILY && target.targetId().equals(line.subfamilyId())));
    }

    private static boolean overlaps(Set<Integer> usedPositions, Set<Integer> positions) {
        for (var position : positions) {
            if (usedPositions.contains(position)) {
                return true;
            }
        }
        return false;
    }

    private static int wholeQuantity(BigDecimal quantity) {
        return quantity == null ? 0 : quantity.intValue();
    }

    private static BigDecimal euros(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private record Unit(PromotionEvaluationLine line) {

        BigDecimal price() {
            return line.unitPrice();
        }
    }
}

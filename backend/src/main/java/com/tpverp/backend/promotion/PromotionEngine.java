package com.tpverp.backend.promotion;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.DocumentLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class PromotionEngine {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MAX_EXACT_UNITS_PER_PROMOTION_TAX_GROUP = 18;

    public PromotionPreview preview(PromotionEvaluationRequest request) {
        var candidates = new ArrayList<Candidate>();
        for (var promotion : request.promotions()) {
            candidates.addAll(candidatesFor(promotion, request));
        }

        candidates.sort(Comparator
                .comparing((Candidate candidate) -> candidate.benefit().amount(), Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.benefit().name() == null ? "" : candidate.benefit().name()));

        var selected = selectBest(candidates).stream()
                .map(Candidate::benefit)
                .toList();

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

    private static List<Candidate> candidatesFor(
            Promotion promotion,
            PromotionEvaluationRequest request) {
        var units = eligibleUnits(promotion, request);
        var unitsByTax = new HashMap<TaxKey, List<Unit>>();
        for (var unit : units) {
            unitsByTax.computeIfAbsent(TaxKey.of(unit.line()), ignored -> new ArrayList<>()).add(unit);
        }
        var result = new ArrayList<Candidate>();
        for (var taxUnits : unitsByTax.values()) {
            result.addAll(taxCandidatesFor(promotion, taxUnits));
        }
        return result;
    }

    private static List<Candidate> taxCandidatesFor(Promotion promotion, List<Unit> units) {
        if (promotion.type() == PromotionType.BUY_X_PAY_Y) {
            return buyXPayYCandidates(promotion, units);
        }
        if (promotion.type() == PromotionType.SECOND_UNIT_PERCENT) {
            return secondUnitPercentCandidates(promotion, units);
        }
        return List.of();
    }

    private static List<Candidate> buyXPayYCandidates(Promotion promotion, List<Unit> units) {
        var buy = wholeQuantity(promotion.buyQuantity());
        var pay = wholeQuantity(promotion.payQuantity());
        if (buy <= 0 || pay < 0 || pay >= buy || units.size() < buy) {
            return List.of();
        }
        if (units.size() <= MAX_EXACT_UNITS_PER_PROMOTION_TAX_GROUP) {
            var combinations = new ArrayList<Candidate>();
            collectBuyXPayYCombinations(promotion, units, buy, pay, 0, new ArrayList<>(), combinations);
            return combinations;
        }

        // Above the first-version exact cap, keep deterministic greedy generation to avoid
        // combinatorial explosion while still producing stable cashier previews.
        var sorted = units.stream()
                .sorted(Comparator.comparing(Unit::price).reversed())
                .toList();
        var result = new ArrayList<Candidate>();
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

    private static List<Candidate> secondUnitPercentCandidates(Promotion promotion, List<Unit> units) {
        if (promotion.discountPercent() == null || promotion.discountPercent().signum() <= 0 || units.size() < 2) {
            return List.of();
        }
        if (units.size() <= MAX_EXACT_UNITS_PER_PROMOTION_TAX_GROUP) {
            var result = new ArrayList<Candidate>();
            for (var first = 0; first < units.size() - 1; first++) {
                for (var second = first + 1; second < units.size(); second++) {
                    result.add(secondUnitPercentCandidate(promotion, List.of(units.get(first), units.get(second))));
                }
            }
            return result;
        }

        // Above the first-version exact cap, keep deterministic greedy generation to avoid
        // combinatorial explosion while still producing stable cashier previews.
        var sorted = units.stream()
                .sorted(Comparator.comparing(Unit::price).reversed())
                .toList();
        var result = new ArrayList<Candidate>();
        for (var start = 0; start + 2 <= sorted.size(); start += 2) {
            var pair = sorted.subList(start, start + 2);
            var cheaper = pair.stream().min(Comparator.comparing(Unit::price)).orElseThrow();
            var discount = cheaper.price().multiply(promotion.discountPercent()).divide(HUNDRED, 6, RoundingMode.HALF_UP);
            result.add(benefit(promotion, pair, discount));
        }
        return result;
    }

    private static void collectBuyXPayYCombinations(
            Promotion promotion,
            List<Unit> units,
            int buy,
            int pay,
            int start,
            List<Unit> selected,
            List<Candidate> result) {
        if (selected.size() == buy) {
            var discounted = selected.stream()
                    .sorted(Comparator.comparing(Unit::price))
                    .limit(buy - pay)
                    .map(Unit::price)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            result.add(benefit(promotion, selected, discounted));
            return;
        }
        var needed = buy - selected.size();
        for (var index = start; index <= units.size() - needed; index++) {
            selected.add(units.get(index));
            collectBuyXPayYCombinations(promotion, units, buy, pay, index + 1, selected, result);
            selected.removeLast();
        }
    }

    private static Candidate secondUnitPercentCandidate(Promotion promotion, List<Unit> pair) {
        var cheaper = pair.stream().min(Comparator.comparing(Unit::price)).orElseThrow();
        var discount = cheaper.price().multiply(promotion.discountPercent()).divide(HUNDRED, 6, RoundingMode.HALF_UP);
        return benefit(promotion, pair, discount);
    }

    private static Candidate benefit(Promotion promotion, List<Unit> units, BigDecimal amount) {
        var first = units.getFirst().line();
        var positions = new HashSet<Integer>();
        var unitKeys = new HashSet<String>();
        for (var unit : units) {
            positions.add(unit.line().position());
            unitKeys.add(unit.key());
        }
        var benefit = new PromotionBenefit(
                promotion.id(),
                promotion.name(),
                positions,
                euros(amount),
                first.taxIncluded(),
                first.taxRegime(),
                first.taxPercent());
        return new Candidate(benefit, unitKeys);
    }

    private static List<Unit> eligibleUnits(Promotion promotion, PromotionEvaluationRequest request) {
        var result = new ArrayList<Unit>();
        for (var line : request.lines()) {
            if (!line.discountable() || line.manualDiscount() || !matches(promotion, line, request.targets())) {
                continue;
            }
            var quantity = wholeQuantity(line.quantity());
            for (var index = 0; index < quantity; index++) {
                result.add(new Unit(line, index + 1));
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

    private static List<Candidate> selectBest(List<Candidate> candidates) {
        return search(candidates, 0, new HashSet<>(), new ArrayList<>(), BigDecimal.ZERO, new Selection(List.of(), BigDecimal.ZERO))
                .candidates();
    }

    private static Selection search(
            List<Candidate> candidates,
            int index,
            Set<String> usedUnits,
            List<Candidate> selected,
            BigDecimal total,
            Selection best) {
        if (index >= candidates.size()) {
            if (total.compareTo(best.total()) > 0) {
                return new Selection(List.copyOf(selected), total);
            }
            return best;
        }

        var bestWithout = search(candidates, index + 1, usedUnits, selected, total, best);
        var candidate = candidates.get(index);
        if (candidate.benefit().amount().signum() <= 0 || overlaps(usedUnits, candidate.unitKeys())) {
            return bestWithout;
        }

        selected.add(candidate);
        usedUnits.addAll(candidate.unitKeys());
        var bestWith = search(
                candidates,
                index + 1,
                usedUnits,
                selected,
                total.add(candidate.benefit().amount()),
                bestWithout);
        selected.removeLast();
        usedUnits.removeAll(candidate.unitKeys());
        return bestWith;
    }

    private static boolean overlaps(Set<String> usedUnits, Set<String> unitKeys) {
        for (var unitKey : unitKeys) {
            if (usedUnits.contains(unitKey)) {
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

    private record Candidate(PromotionBenefit benefit, Set<String> unitKeys) {

        private Candidate {
            unitKeys = Set.copyOf(unitKeys);
        }
    }

    private record Selection(List<Candidate> candidates, BigDecimal total) {
    }

    private record TaxKey(boolean included, String regime, BigDecimal percent) {

        private static TaxKey of(PromotionEvaluationLine line) {
            return new TaxKey(line.taxIncluded(), line.taxRegime(), line.taxPercent());
        }
    }

    private record Unit(PromotionEvaluationLine line, int index) {

        BigDecimal price() {
            return line.unitPrice();
        }

        String key() {
            return line.position() + "#" + index;
        }
    }
}

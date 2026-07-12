package com.tpverp.backend.promotion;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.DocumentLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

@Service
public class PromotionEngine {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MAX_EXACT_UNITS_PER_GROUP = 18;
    private static final int MAX_EXACT_CANDIDATES = 24;
    private static final int MAX_EXPANDED_UNITS = 10_000;

    public PromotionPreview preview(PromotionEvaluationRequest request) {
        var candidates = new ArrayList<Candidate>();
        for (var promotion : request.promotions()) {
            candidates.addAll(candidatesFor(promotion, request));
        }

        candidates.sort(Comparator
                .comparing(Candidate::amount, Comparator.reverseOrder())
                .thenComparing(Candidate::name)
                .thenComparing(candidate -> candidate.unitKeys().toString()));

        var selectedCandidates = candidates.size() > MAX_EXACT_CANDIDATES
                ? selectGreedy(candidates)
                : selectBest(candidates);
        var selected = selectedCandidates.stream()
                .flatMap(candidate -> candidate.benefits().stream())
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
        var lines = PromotionEligibility.eligibleLines(
                promotion, request.lines(), request.targets());
        if (lines.isEmpty()) {
            return List.of();
        }
        return switch (promotion.type()) {
            case PURCHASE_THRESHOLD_COUPON -> List.of();
            case PURCHASE_THRESHOLD_DISCOUNT -> thresholdDiscountCandidate(
                    promotion, lines, promotion.minimumAmount(), null);
            case BUY_X_PAY_Y -> buyXPayYCandidates(promotion, lines);
            case SECOND_UNIT_PERCENT -> secondUnitPercentCandidates(promotion, lines);
            case FIXED_PACK_PRICE -> fixedPackPriceCandidates(promotion, lines);
            case QUANTITY_DISCOUNT -> thresholdDiscountCandidate(
                    promotion, lines, null, promotion.minimumQuantity());
        };
    }

    private static List<Candidate> thresholdDiscountCandidate(
            Promotion promotion,
            List<PromotionEvaluationLine> lines,
            BigDecimal minimumAmount,
            BigDecimal minimumQuantity) {
        var subtotal = PromotionEligibility.subtotal(lines);
        var payableSubtotal = PromotionEligibility.payableSubtotal(lines);
        var quantity = PromotionEligibility.quantity(lines);
        if ((minimumAmount != null && payableSubtotal.compareTo(minimumAmount) < 0)
                || (minimumQuantity != null && quantity.compareTo(minimumQuantity) < 0)) {
            return List.of();
        }
        var discount = discountAmount(promotion, subtotal);
        if (discount.signum() <= 0) {
            return List.of();
        }
        var benefits = allocatedBenefits(promotion, lines, discount, subtotal);
        return benefits.isEmpty()
                ? List.of()
                : List.of(new Candidate(benefits, lineUnitKeys(lines)));
    }

    private static BigDecimal discountAmount(Promotion promotion, BigDecimal subtotal) {
        BigDecimal discount;
        if (promotion.discountAmount() != null) {
            discount = promotion.discountAmount();
        } else if (promotion.discountPercent() != null) {
            discount = subtotal.multiply(promotion.discountPercent())
                    .divide(HUNDRED, 6, RoundingMode.HALF_UP);
        } else {
            return BigDecimal.ZERO;
        }
        if (promotion.maximumDiscount() != null) {
            discount = discount.min(promotion.maximumDiscount());
        }
        return euros(discount.min(subtotal));
    }

    private static List<PromotionBenefit> allocatedBenefits(
            Promotion promotion,
            List<PromotionEvaluationLine> lines,
            BigDecimal discount,
            BigDecimal subtotal) {
        var byTax = new LinkedHashMap<TaxKey, List<PromotionEvaluationLine>>();
        for (var line : lines) {
            byTax.computeIfAbsent(TaxKey.of(line), ignored -> new ArrayList<>()).add(line);
        }
        var result = new ArrayList<PromotionBenefit>();
        var remaining = discount;
        var entries = new ArrayList<>(byTax.entrySet());
        for (var index = 0; index < entries.size(); index++) {
            var entry = entries.get(index);
            var groupSubtotal = PromotionEligibility.subtotal(entry.getValue());
            var amount = index == entries.size() - 1
                    ? remaining
                    : euros(discount.multiply(groupSubtotal)
                    .divide(subtotal, 8, RoundingMode.HALF_UP));
            amount = amount.min(groupSubtotal).min(remaining);
            remaining = euros(remaining.subtract(amount));
            if (amount.signum() <= 0) {
                continue;
            }
            var positions = entry.getValue().stream()
                    .map(PromotionEvaluationLine::position)
                    .collect(java.util.stream.Collectors.toSet());
            result.add(benefit(promotion, positions, amount, entry.getKey()));
        }
        return result;
    }

    private static List<Candidate> buyXPayYCandidates(
            Promotion promotion,
            List<PromotionEvaluationLine> lines) {
        var buy = wholeQuantity(promotion.buyQuantity());
        var pay = wholeQuantity(promotion.payQuantity());
        if (buy <= 0 || pay < 0 || pay >= buy) {
            return List.of();
        }
        var grouped = groupedUnits(
                lines,
                promotion.buyXPayYMode() == BuyXPayYMode.SAME_PRODUCT);
        var result = new ArrayList<Candidate>();
        for (var units : grouped.values()) {
            result.addAll(buyXPayYCandidatesForGroup(promotion, units, buy, pay));
        }
        return result;
    }

    private static List<Candidate> buyXPayYCandidatesForGroup(
            Promotion promotion,
            List<Unit> units,
            int buy,
            int pay) {
        if (units.size() < buy) {
            return List.of();
        }
        if (units.size() <= MAX_EXACT_UNITS_PER_GROUP) {
            var combinations = new ArrayList<Candidate>();
            if (collectPackCombinations(
                    promotion,
                    units,
                    buy,
                    0,
                    new ArrayList<>(),
                    combinations,
                    pack -> pack.stream()
                            .sorted(Comparator.comparing(Unit::price))
                            .limit(buy - pay)
                            .map(Unit::price)
                            .reduce(BigDecimal.ZERO, BigDecimal::add))) {
                return combinations;
            }
        }
        return greedyPackCandidates(
                promotion,
                units,
                buy,
                pack -> pack.stream()
                        .sorted(Comparator.comparing(Unit::price))
                        .limit(buy - pay)
                        .map(Unit::price)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static List<Candidate> secondUnitPercentCandidates(
            Promotion promotion,
            List<PromotionEvaluationLine> lines) {
        if (promotion.discountPercent() == null || promotion.discountPercent().signum() <= 0) {
            return List.of();
        }
        var result = new ArrayList<Candidate>();
        for (var units : groupedUnits(lines, false).values()) {
            if (units.size() < 2) {
                continue;
            }
            if (units.size() <= MAX_EXACT_UNITS_PER_GROUP) {
                for (var first = 0; first < units.size() - 1; first++) {
                    for (var second = first + 1; second < units.size(); second++) {
                        if (result.size() == MAX_EXACT_CANDIDATES) {
                            return greedySecondUnitPercentCandidates(promotion, lines);
                        }
                        result.add(secondUnitPercentCandidate(
                                promotion, List.of(units.get(first), units.get(second))));
                    }
                }
            } else {
                return greedySecondUnitPercentCandidates(promotion, lines);
            }
        }
        return result;
    }

    private static List<Candidate> greedySecondUnitPercentCandidates(
            Promotion promotion,
            List<PromotionEvaluationLine> lines) {
        var result = new ArrayList<Candidate>();
        for (var units : groupedUnits(lines, false).values()) {
            var sorted = units.stream()
                    .sorted(Comparator.comparing(Unit::price).reversed())
                    .toList();
            for (var start = 0; start + 2 <= sorted.size(); start += 2) {
                result.add(secondUnitPercentCandidate(
                        promotion, sorted.subList(start, start + 2)));
            }
        }
        return result;
    }

    private static Candidate secondUnitPercentCandidate(
            Promotion promotion,
            List<Unit> pair) {
        var cheaper = pair.stream().min(Comparator.comparing(Unit::price)).orElseThrow();
        var discount = cheaper.price().multiply(promotion.discountPercent())
                .divide(HUNDRED, 6, RoundingMode.HALF_UP);
        return unitCandidate(promotion, pair, discount);
    }

    private static List<Candidate> fixedPackPriceCandidates(
            Promotion promotion,
            List<PromotionEvaluationLine> lines) {
        var quantity = wholeQuantity(promotion.buyQuantity());
        if (quantity <= 0 || promotion.packPrice() == null) {
            return List.of();
        }
        Function<List<Unit>, BigDecimal> discount = pack -> pack.stream()
                .map(Unit::price)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .subtract(promotion.packPrice())
                .max(BigDecimal.ZERO);
        var result = new ArrayList<Candidate>();
        for (var units : groupedUnits(lines, false).values()) {
            if (units.size() < quantity) {
                continue;
            }
            if (units.size() <= MAX_EXACT_UNITS_PER_GROUP) {
                var combinations = new ArrayList<Candidate>();
                if (collectPackCombinations(
                        promotion, units, quantity, 0, new ArrayList<>(), combinations, discount)) {
                    result.addAll(combinations);
                    continue;
                }
            }
            result.addAll(greedyPackCandidates(promotion, units, quantity, discount));
        }
        return result;
    }

    private static boolean collectPackCombinations(
            Promotion promotion,
            List<Unit> units,
            int packSize,
            int start,
            List<Unit> selected,
            List<Candidate> result,
            Function<List<Unit>, BigDecimal> discount) {
        if (selected.size() == packSize) {
            if (result.size() == MAX_EXACT_CANDIDATES) {
                return false;
            }
            var candidate = unitCandidate(promotion, selected, discount.apply(selected));
            if (candidate.amount().signum() > 0) {
                result.add(candidate);
            }
            return true;
        }
        var needed = packSize - selected.size();
        for (var index = start; index <= units.size() - needed; index++) {
            selected.add(units.get(index));
            if (!collectPackCombinations(
                    promotion, units, packSize, index + 1, selected, result, discount)) {
                selected.removeLast();
                return false;
            }
            selected.removeLast();
        }
        return true;
    }

    private static List<Candidate> greedyPackCandidates(
            Promotion promotion,
            List<Unit> units,
            int packSize,
            Function<List<Unit>, BigDecimal> discount) {
        var sorted = units.stream()
                .sorted(Comparator.comparing(Unit::price).reversed())
                .toList();
        var result = new ArrayList<Candidate>();
        for (var start = 0; start + packSize <= sorted.size(); start += packSize) {
            var pack = sorted.subList(start, start + packSize);
            var candidate = unitCandidate(promotion, pack, discount.apply(pack));
            if (candidate.amount().signum() > 0) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static LinkedHashMap<GroupKey, List<Unit>> groupedUnits(
            List<PromotionEvaluationLine> lines,
            boolean sameProduct) {
        var result = new LinkedHashMap<GroupKey, List<Unit>>();
        var expanded = 0;
        for (var line : lines) {
            var quantity = wholeQuantity(line.quantity());
            if (quantity <= 0 || expanded + quantity > MAX_EXPANDED_UNITS) {
                continue;
            }
            var key = new GroupKey(
                    TaxKey.of(line),
                    sameProduct ? line.productId() : null);
            var units = result.computeIfAbsent(key, ignored -> new ArrayList<>());
            for (var index = 1; index <= quantity; index++) {
                units.add(new Unit(line, index));
            }
            expanded += quantity;
        }
        return result;
    }

    private static Candidate unitCandidate(
            Promotion promotion,
            List<Unit> units,
            BigDecimal amount) {
        var first = units.getFirst().line();
        var positions = units.stream()
                .map(unit -> unit.line().position())
                .collect(java.util.stream.Collectors.toSet());
        var keys = units.stream()
                .map(Unit::key)
                .collect(java.util.stream.Collectors.toSet());
        return new Candidate(
                List.of(benefit(promotion, positions, euros(amount), TaxKey.of(first))),
                keys);
    }

    private static PromotionBenefit benefit(
            Promotion promotion,
            Set<Integer> positions,
            BigDecimal amount,
            TaxKey tax) {
        return new PromotionBenefit(
                promotion.rootVersionId(),
                promotion.id(),
                promotion.name(),
                positions,
                euros(amount),
                tax.included(),
                tax.regime(),
                tax.percent());
    }

    private static Set<String> lineUnitKeys(List<PromotionEvaluationLine> lines) {
        var result = new HashSet<String>();
        for (var line : lines) {
            var quantity = wholeQuantity(line.quantity());
            if (quantity > 0 && quantity <= MAX_EXPANDED_UNITS) {
                for (var index = 1; index <= quantity; index++) {
                    result.add(line.position() + "#" + index);
                }
            } else {
                result.add(line.position() + "#ALL");
            }
        }
        return result;
    }

    private static List<Candidate> selectBest(List<Candidate> candidates) {
        return search(
                candidates,
                0,
                new HashSet<>(),
                new ArrayList<>(),
                BigDecimal.ZERO,
                new Selection(List.of(), BigDecimal.ZERO))
                .candidates();
    }

    private static List<Candidate> selectGreedy(List<Candidate> candidates) {
        var selected = new ArrayList<Candidate>();
        var usedUnits = new HashSet<String>();
        for (var candidate : candidates) {
            if (candidate.amount().signum() <= 0 || overlaps(usedUnits, candidate.unitKeys())) {
                continue;
            }
            selected.add(candidate);
            usedUnits.addAll(candidate.unitKeys());
        }
        return selected;
    }

    private static Selection search(
            List<Candidate> candidates,
            int index,
            Set<String> usedUnits,
            List<Candidate> selected,
            BigDecimal total,
            Selection best) {
        if (index >= candidates.size()) {
            return total.compareTo(best.total()) > 0
                    ? new Selection(List.copyOf(selected), total)
                    : best;
        }
        var bestWithout = search(candidates, index + 1, usedUnits, selected, total, best);
        var candidate = candidates.get(index);
        if (candidate.amount().signum() <= 0 || overlaps(usedUnits, candidate.unitKeys())) {
            return bestWithout;
        }
        selected.add(candidate);
        usedUnits.addAll(candidate.unitKeys());
        var bestWith = search(
                candidates,
                index + 1,
                usedUnits,
                selected,
                total.add(candidate.amount()),
                bestWithout);
        selected.removeLast();
        usedUnits.removeAll(candidate.unitKeys());
        return bestWith;
    }

    private static boolean overlaps(Set<String> usedUnits, Set<String> unitKeys) {
        for (var unitKey : unitKeys) {
            if (usedUnits.contains(unitKey)
                    || (unitKey.endsWith("#ALL") && usedUnits.stream()
                    .anyMatch(used -> used.startsWith(unitKey.substring(0, unitKey.length() - 3))))
                    || usedUnits.stream().anyMatch(used -> used.endsWith("#ALL")
                    && unitKey.startsWith(used.substring(0, used.length() - 3)))) {
                return true;
            }
        }
        return false;
    }

    private static int wholeQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0
                || quantity.stripTrailingZeros().scale() > 0
                || quantity.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            return -1;
        }
        return quantity.intValueExact();
    }

    private static BigDecimal euros(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private record Candidate(List<PromotionBenefit> benefits, Set<String> unitKeys) {

        private Candidate {
            benefits = List.copyOf(benefits);
            unitKeys = Set.copyOf(unitKeys);
        }

        BigDecimal amount() {
            return benefits.stream()
                    .map(PromotionBenefit::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        String name() {
            return benefits.isEmpty() || benefits.getFirst().name() == null
                    ? ""
                    : benefits.getFirst().name();
        }
    }

    private record Selection(List<Candidate> candidates, BigDecimal total) {
    }

    private record GroupKey(TaxKey tax, UUID productId) {
    }

    private record TaxKey(boolean included, String regime, BigDecimal percent) {

        private static TaxKey of(PromotionEvaluationLine line) {
            return new TaxKey(
                    line.taxIncluded(),
                    line.taxRegime(),
                    line.taxPercent().stripTrailingZeros());
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

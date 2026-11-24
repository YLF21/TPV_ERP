package com.tpverp.backend.promotion;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.DocumentLine;
import com.tpverp.backend.document.DocumentLineType;
import com.tpverp.backend.document.QuantityPriceSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

@Service
public class PromotionEngine {

    public static final int CURRENT_VERSION = 3;

    public static int versionFor(PromotionEvaluationRequest request) {
        return request.quantityPrices().isEmpty() ? 2 : CURRENT_VERSION;
    }

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MAX_EXACT_UNITS_PER_GROUP = 18;
    private static final int MAX_EXACT_CANDIDATES = 24;
    private static final int MAX_EXPANDED_UNITS = 10_000;

    public PromotionPreview preview(PromotionEvaluationRequest request) {
        if (versionFor(request) == 2) return previewVersion2(request);
        var quantityPrices = quantityPriceCandidates(request).stream()
                .map(candidate -> candidate.benefits().getFirst()).toList();
        return MixedBasketPricing.preview(request, quantityPrices);
    }

    /** Replays tickets issued by the previous candidate-based engine. */
    public PromotionPreview previewVersion2(PromotionEvaluationRequest request) {
        return preview(request, false);
    }

    public PromotionPreview previewLegacy(PromotionEvaluationRequest request) {
        return preview(request, true);
    }

    private PromotionPreview preview(PromotionEvaluationRequest request, boolean legacy) {
        var candidates = new ArrayList<Candidate>();
        for (var promotion : request.promotions()) {
            candidates.addAll(candidatesFor(promotion, request, legacy));
        }
        if (!legacy) candidates.addAll(quantityPriceCandidates(request));
        if (legacy) {
            candidates.replaceAll(candidate -> new Candidate(candidate.benefits(), candidate.unitKeys(), false));
        }

        candidates.sort(Comparator
                .comparing(Candidate::amount, Comparator.reverseOrder())
                .thenComparing(Candidate::name)
                .thenComparing(candidate -> candidate.unitKeys().toString()));

        var selectedCandidates = candidates.size() > MAX_EXACT_CANDIDATES
                ? selectLarge(candidates)
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
            var line = benefitLine(document, position++, benefit);
            line.assignPromotionEngineVersion(preview.engineVersion());
            line.assignPromotionAffectedPositions(benefit.affectedPositions());
            result.add(line);
        }
        return result;
    }

    public static DocumentLine benefitLine(CommercialDocument document, int position, PromotionBenefit benefit) {
        if (benefit.quantityPrice()) return quantityPriceLine(document, position, benefit);
        var amounts = benefit.quantityAmounts();
        if (amounts == null) amounts = quantityAmounts(benefit.amount(),
                new TaxKey(benefit.taxIncluded(), benefit.taxRegime(), benefit.taxPercent()));
        return DocumentLine.frozenSpecial(document, position,
                DocumentLineType.PROMOTION, "PROMOCION " + benefit.name(),
                benefit.amount().negate(), benefit.taxIncluded(), benefit.taxRegime(), benefit.taxPercent(),
                benefit.promotionId(), benefit.promotionVersionId(), null, amounts.base().negate(),
                amounts.tax().negate(), amounts.total().negate());
    }

    public static DocumentLine quantityPriceLine(CommercialDocument document, int position, PromotionBenefit benefit) {
        var amounts = benefit.quantityAmounts();
        return amounts == null
                ? DocumentLine.quantityPrice(document, position, benefit.name(), benefit.amount().negate(),
                        benefit.taxIncluded(), benefit.taxRegime(), benefit.taxPercent())
                : DocumentLine.quantityPrice(document, position, benefit.name(), benefit.amount().negate(),
                        benefit.taxIncluded(), benefit.taxRegime(), benefit.taxPercent(),
                        amounts.base().negate(), amounts.tax().negate(), amounts.total().negate());
    }

    private static List<Candidate> quantityPriceCandidates(PromotionEvaluationRequest request) {
        var grouped = new LinkedHashMap<QuantityGroup, List<PromotionEvaluationLine>>();
        for (var line : request.lines()) {
            var snapshot = request.quantityPrices().get(line.position());
            if (snapshot == null || !line.discountable() || line.manualDiscount()) continue;
            var key = new QuantityGroup(line.productId(), TaxKey.of(line), snapshot);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(line);
        }
        var result = new ArrayList<Candidate>();
        for (var entry : grouped.entrySet()) {
            var lines = entry.getValue();
            var snapshot = entry.getKey().snapshot();
            var quantity = PromotionEligibility.quantity(lines);
            var calculation = QuantityPricingCalculator.calculate(snapshot.rule(), snapshot.salePrice(), quantity);
            var total = calculation.total();
            if (calculation.appliedTiers().isEmpty()) continue;
            // Each original document row has already rounded its monetary amount.
            var baseline = lines.stream().map(line -> euros(line.unitPrice().multiply(line.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            var saving = euros(baseline.subtract(total));
            if (saving.signum() <= 0) continue;
            var tax = entry.getKey().tax();
            var baselineBase = BigDecimal.ZERO;
            var baselineTax = BigDecimal.ZERO;
            var baselineTotal = BigDecimal.ZERO;
            for (var line : lines) {
                var amounts = quantityAmounts(euros(line.unitPrice().multiply(line.quantity())), tax);
                baselineBase = baselineBase.add(amounts.base());
                baselineTax = baselineTax.add(amounts.tax());
                baselineTotal = baselineTotal.add(amounts.total());
            }
            var target = quantityAmounts(total, tax);
            var reduction = new PromotionBenefit.QuantityAmounts(
                    euros(baselineBase.subtract(target.base())), euros(baselineTax.subtract(target.tax())),
                    euros(baselineTotal.subtract(target.total())));
            if (reduction.total().signum() <= 0) continue;
            var positions = lines.stream().map(PromotionEvaluationLine::position)
                    .collect(java.util.stream.Collectors.toSet());
            var benefit = new PromotionBenefit(null, null, calculation.description(), positions,
                    saving, tax.included(), tax.regime(), tax.percent(), true, reduction);
            // A quantity rate prices the complete product group: its units cannot
            // simultaneously receive another automatic promotion.
            var keys = lines.stream().map(line -> line.position() + "#ALL")
                    .collect(java.util.stream.Collectors.toSet());
            result.add(new Candidate(List.of(benefit), keys));
        }
        return result;
    }

    static PromotionBenefit.QuantityAmounts quantityAmounts(BigDecimal amount, TaxKey tax) {
        var base = tax.included() ? euros(amount.divide(
                BigDecimal.ONE.add(tax.percent().movePointLeft(2)), 6, RoundingMode.HALF_UP)) : amount;
        var taxAmount = tax.included() ? euros(amount.subtract(base))
                : euros(base.multiply(tax.percent().movePointLeft(2)));
        return new PromotionBenefit.QuantityAmounts(base, taxAmount, euros(base.add(taxAmount)));
    }

    private static List<Candidate> candidatesFor(
            Promotion promotion,
            PromotionEvaluationRequest request,
            boolean legacy) {
        var lines = PromotionEligibility.eligibleLines(
                promotion, request.lines(), request.targets());
        if (lines.isEmpty()) {
            return List.of();
        }
        return switch (promotion.type()) {
            case PURCHASE_THRESHOLD_COUPON -> List.of();
            case PURCHASE_THRESHOLD_DISCOUNT -> thresholdDiscountCandidate(
                    promotion, lines, promotion.minimumAmount(), null);
            case BUY_X_PAY_Y -> buyXPayYCandidates(promotion, lines, legacy);
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
        var benefits = thresholdBenefits(promotion, lines, minimumAmount, minimumQuantity);
        return benefits.isEmpty() ? List.of() : List.of(new Candidate(benefits, lineUnitKeys(lines)));
    }

    static List<PromotionBenefit> thresholdBenefits(
            Promotion promotion, List<PromotionEvaluationLine> lines, BigDecimal minimumAmount, BigDecimal minimumQuantity) {
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
        return allocatedBenefits(promotion, lines, discount, subtotal);
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
            List<PromotionEvaluationLine> lines,
            boolean legacy) {
        var buy = wholeQuantity(promotion.buyQuantity());
        var pay = wholeQuantity(promotion.payQuantity());
        if (buy <= 0 || pay < 0 || pay >= buy || (legacy && pay == 0)) {
            return List.of();
        }
        var grouped = groupedUnits(
                lines,
                promotion.buyXPayYMode() == BuyXPayYMode.SAME_PRODUCT,
                legacy);
        var result = new ArrayList<Candidate>();
        for (var units : grouped.values()) {
            result.addAll(buyXPayYCandidatesForGroup(promotion, units, buy, pay, legacy));
        }
        return result;
    }

    private static List<Candidate> buyXPayYCandidatesForGroup(
            Promotion promotion,
            List<Unit> units,
            int buy,
            int pay,
            boolean legacy) {
        if (units.size() < buy) {
            return List.of();
        }
        Function<List<Unit>, Candidate> createCandidate = legacy
                ? pack -> unitCandidate(promotion, pack, pack.stream().sorted(Comparator.comparing(Unit::price))
                        .limit(buy - pay).map(Unit::price).reduce(BigDecimal.ZERO, BigDecimal::add))
                : pack -> buyXPayYCandidate(promotion, pack, buy - pay);
        if (units.size() <= MAX_EXACT_UNITS_PER_GROUP) {
            var combinations = new ArrayList<Candidate>();
            if (collectPackCombinations(
                    promotion,
                    units,
                    buy,
                    0,
                    new ArrayList<>(),
                    combinations,
                    createCandidate)) {
                return combinations;
            }
        }
        return greedyPackCandidates(
                promotion,
                units,
                buy,
                createCandidate,
                legacy ? Comparator.comparing(Unit::price).reversed()
                        : Comparator.comparing(Unit::payablePrice).reversed());
    }

    private static Candidate buyXPayYCandidate(Promotion promotion, List<Unit> pack, int freeCount) {
        var freeUnits = pack.stream()
                .sorted(Comparator.comparing(Unit::payablePrice))
                .limit(freeCount)
                .toList();
        var amountsByTax = new LinkedHashMap<TaxKey, BigDecimal>();
        for (var unit : freeUnits) {
            amountsByTax.merge(TaxKey.of(unit.line()), unit.price(), BigDecimal::add);
        }
        var positions = pack.stream().map(unit -> unit.line().position())
                .collect(java.util.stream.Collectors.toSet());
        var benefits = amountsByTax.entrySet().stream()
                .filter(entry -> entry.getValue().signum() > 0)
                .map(entry -> benefit(promotion, positions, entry.getValue(), entry.getKey()))
                .toList();
        var keys = pack.stream().map(Unit::key).collect(java.util.stream.Collectors.toSet());
        return new Candidate(benefits, keys);
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
                        promotion, units, quantity, 0, new ArrayList<>(), combinations,
                        pack -> unitCandidate(promotion, pack, discount.apply(pack)))) {
                    result.addAll(combinations);
                    continue;
                }
            }
            result.addAll(greedyPackCandidates(promotion, units, quantity,
                    pack -> unitCandidate(promotion, pack, discount.apply(pack)),
                    Comparator.comparing(Unit::price).reversed()));
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
            Function<List<Unit>, Candidate> createCandidate) {
        if (selected.size() == packSize) {
            if (result.size() == MAX_EXACT_CANDIDATES) {
                return false;
            }
            var candidate = createCandidate.apply(selected);
            if (candidate.amount().signum() > 0) {
                result.add(candidate);
            }
            return true;
        }
        var needed = packSize - selected.size();
        for (var index = start; index <= units.size() - needed; index++) {
            selected.add(units.get(index));
            if (!collectPackCombinations(
                    promotion, units, packSize, index + 1, selected, result, createCandidate)) {
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
            Function<List<Unit>, Candidate> createCandidate,
            Comparator<Unit> order) {
        var sorted = units.stream()
                .sorted(order)
                .toList();
        var result = new ArrayList<Candidate>();
        for (var start = 0; start + packSize <= sorted.size(); start += packSize) {
            var pack = sorted.subList(start, start + packSize);
            var candidate = createCandidate.apply(pack);
            if (candidate.amount().signum() > 0) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static LinkedHashMap<GroupKey, List<Unit>> groupedUnits(
            List<PromotionEvaluationLine> lines,
            boolean sameProduct) {
        return groupedUnits(lines, sameProduct, true);
    }

    private static LinkedHashMap<GroupKey, List<Unit>> groupedUnits(
            List<PromotionEvaluationLine> lines,
            boolean sameProduct,
            boolean sameTax) {
        var result = new LinkedHashMap<GroupKey, List<Unit>>();
        var expanded = 0;
        for (var line : lines) {
            var quantity = wholeQuantity(line.quantity());
            if (quantity <= 0 || expanded + quantity > MAX_EXPANDED_UNITS) {
                continue;
            }
            var key = new GroupKey(
                    sameTax ? TaxKey.of(line) : null,
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

    static PromotionBenefit benefit(
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

    private static List<Candidate> selectLarge(List<Candidate> candidates) {
        if (candidates.stream().noneMatch(Candidate::quantityPrice)) return selectGreedy(candidates);
        // Equal consumed units are interchangeable for every other candidate.
        // Keep the largest payable benefit (input is sorted by that amount).
        var distinct = new LinkedHashMap<Consumption, Candidate>();
        for (var candidate : candidates) {
            distinct.putIfAbsent(new Consumption(candidate.unitKeys(), candidate.quantityPrice()), candidate);
        }
        candidates = List.copyOf(distinct.values());
        var graph = conflictGraph(candidates);
        var pending = new BitSet(candidates.size());
        pending.set(0, candidates.size());
        var result = new ArrayList<Candidate>();
        while (!pending.isEmpty()) {
            var component = new BitSet(candidates.size());
            var frontier = new BitSet(candidates.size());
            frontier.set(pending.nextSetBit(0));
            while (!frontier.isEmpty()) {
                var index = frontier.nextSetBit(0);
                frontier.clear(index);
                if (component.get(index)) continue;
                component.set(index);
                pending.clear(index);
                var neighbors = (BitSet) graph[index].clone();
                neighbors.andNot(component);
                frontier.or(neighbors);
            }
            var values = component.stream().mapToObj(candidates::get).toList();
            if (values.stream().noneMatch(Candidate::quantityPrice)) {
                result.addAll(values.size() <= MAX_EXACT_CANDIDATES ? selectBest(values) : selectGreedy(values));
            } else {
                result.addAll(new ExactComponentSelection(candidates, graph, component).solve());
            }
        }
        return result;
    }

    private static BitSet[] conflictGraph(List<Candidate> candidates) {
        var owners = new HashMap<String, BitSet>();
        var positions = new HashMap<String, BitSet>();
        for (int index = 0; index < candidates.size(); index++) {
            for (var key : candidates.get(index).unitKeys()) {
                owners.computeIfAbsent(key, ignored -> new BitSet()).set(index);
                var position = key.substring(0, key.indexOf('#'));
                positions.computeIfAbsent(position, ignored -> new BitSet()).set(index);
            }
        }
        var graph = new BitSet[candidates.size()];
        for (int index = 0; index < candidates.size(); index++) graph[index] = new BitSet();
        for (var entry : owners.entrySet()) {
            var key = entry.getKey();
            var participants = key.endsWith("#ALL")
                    ? positions.get(key.substring(0, key.indexOf('#'))) : entry.getValue();
            for (int index = entry.getValue().nextSetBit(0); index >= 0;
                    index = entry.getValue().nextSetBit(index + 1)) {
                graph[index].or(participants);
                for (int other = participants.nextSetBit(0); other >= 0;
                        other = participants.nextSetBit(other + 1)) graph[other].set(index);
            }
        }
        for (int index = 0; index < graph.length; index++) graph[index].clear(index);
        return graph;
    }

    /** Exact weighted independent set for each component interacting with a quantity rate. */
    private static final class ExactComponentSelection {
        private final List<Candidate> candidates;
        private final BitSet[] graph;
        private final BitSet component;
        private final BigDecimal[] weights;
        private List<Integer> best = List.of();
        private BigDecimal bestAmount = BigDecimal.ZERO;

        private ExactComponentSelection(List<Candidate> candidates, BitSet[] graph, BitSet component) {
            this.candidates = candidates;
            this.graph = graph;
            this.component = component;
            weights = candidates.stream().map(Candidate::amount).toArray(BigDecimal[]::new);
        }

        private List<Candidate> solve() {
            search(component, new ArrayList<>(), BigDecimal.ZERO);
            return best.stream().map(candidates::get).toList();
        }

        private void search(BitSet available, List<Integer> selected, BigDecimal amount) {
            var upperBound = amount;
            for (int index = available.nextSetBit(0); index >= 0; index = available.nextSetBit(index + 1)) {
                upperBound = upperBound.add(weights[index]);
            }
            if (upperBound.compareTo(bestAmount) <= 0) return;
            var remaining = (BitSet) available.clone();
            var originalSize = selected.size();
            int pivot = -1;
            int largestDegree = -1;
            for (int index = available.nextSetBit(0); index >= 0; index = available.nextSetBit(index + 1)) {
                var conflicts = (BitSet) graph[index].clone();
                conflicts.and(available);
                var degree = conflicts.cardinality();
                if (degree == 0) {
                    // A positive isolated benefit is mandatory in every optimum.
                    selected.add(index);
                    amount = amount.add(weights[index]);
                    remaining.clear(index);
                } else if (degree > largestDegree) {
                    largestDegree = degree;
                    pivot = index;
                }
            }
            if (pivot < 0) {
                if (amount.compareTo(bestAmount) > 0) {
                    bestAmount = amount;
                    best = List.copyOf(selected);
                }
            } else {
                remaining.clear(pivot);
                var with = (BitSet) remaining.clone();
                with.andNot(graph[pivot]);
                selected.add(pivot);
                search(with, selected, amount.add(weights[pivot]));
                selected.removeLast();
                search(remaining, selected, amount);
            }
            while (selected.size() > originalSize) selected.removeLast();
        }
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
        if (quantity == null || quantity.signum() < 0
                || quantity.stripTrailingZeros().scale() > 0
                || quantity.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            return -1;
        }
        return quantity.intValueExact();
    }

    static BigDecimal euros(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private record Candidate(List<PromotionBenefit> benefits, Set<String> unitKeys, boolean finalAmounts) {

        private Candidate(List<PromotionBenefit> benefits, Set<String> unitKeys) {
            this(benefits, unitKeys, true);
        }

        private Candidate {
            benefits = List.copyOf(benefits);
            unitKeys = Set.copyOf(unitKeys);
        }

        BigDecimal amount() {
            return benefits.stream().map(finalAmounts ? PromotionBenefit::payableAmount : PromotionBenefit::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        String name() {
            return benefits.isEmpty() || benefits.getFirst().name() == null
                    ? ""
                    : benefits.getFirst().name();
        }

        boolean quantityPrice() {
            return !benefits.isEmpty() && benefits.getFirst().quantityPrice();
        }
    }

    private record Selection(List<Candidate> candidates, BigDecimal total) {
    }

    private record Consumption(Set<String> units, boolean quantityPrice) {
    }

    private record GroupKey(TaxKey tax, UUID productId) {
    }

    private record QuantityGroup(UUID productId, TaxKey tax, QuantityPriceSnapshot snapshot) {
    }

    record TaxKey(boolean included, String regime, BigDecimal percent) {

        static TaxKey of(PromotionEvaluationLine line) {
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

        BigDecimal payablePrice() {
            return line.payableUnitAmount();
        }

        String key() {
            return line.position() + "#" + index;
        }
    }
}

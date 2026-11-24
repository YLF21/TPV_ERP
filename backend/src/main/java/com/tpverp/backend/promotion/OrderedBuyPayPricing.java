package com.tpverp.backend.promotion;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Certified ordered solution for a single X-for-(X-1) promotion.
 *
 * For a fixed choice of whole-product quantity rates, sort the remaining prices
 * p1 >= p2 >= ... . If the free prices of any k disjoint X-unit packs are sorted
 * f1 >= ... >= fk, the first j packs need j*X units priced at least fj. Thus
 * fj <= p(j*X). Consecutive ordered packs attain every bound simultaneously.
 * Included cent-exact prices make these bounds additive without tax-rounding
 * differences. SAME_PRODUCT applies the proof independently to each product.
 * Enumerating all competing quantity-rate selections therefore certifies the
 * global optimum in this restricted case. Unsupported cases return null and
 * retain the general exact solver; no work limit changes the financial result.
 */
final class OrderedBuyPayPricing {
    private static final int MAX_COMPETING_RATES = 8;
    private static final long MAX_LINE_VISITS = 2_000_000;

    private OrderedBuyPayPricing() { }

    static PromotionPreview tryPreview(PromotionEvaluationRequest request, List<PromotionBenefit> quantityPrices) {
        if (request.quantityPrices().isEmpty() || request.promotions().size() != 1) return null;
        var promotion = request.promotions().getFirst();
        if (promotion.type() != PromotionType.BUY_X_PAY_Y) return null;
        long size = whole(promotion.buyQuantity());
        long paid = whole(promotion.payQuantity());
        if (size <= 0 || paid < 0 || paid != size - 1) return null;

        var eligible = new ArrayList<PromotionEvaluationLine>();
        for (var line : PromotionEligibility.eligibleLines(promotion, request.lines(), request.targets())) {
            // Fractional source rows never supply units to classic pack promotions.
            if (line.quantity().stripTrailingZeros().scale() > 0) continue;
            if (whole(line.quantity()) < 0 || !line.taxIncluded()
                    || line.unitPrice().stripTrailingZeros().scale() > 2
                    || line.payableUnitAmount().compareTo(line.unitPrice()) != 0
                    || line.taxPercent().signum() < 0) return null;
            eligible.add(line);
        }
        var eligiblePositions = new HashSet<Integer>();
        for (var line : eligible) if (!eligiblePositions.add(line.position())) return null;
        var allPositions = new HashSet<Integer>();
        for (var line : request.lines()) if (!allPositions.add(line.position())) return null;
        var seenQuantityPositions = new HashSet<Integer>();
        var competing = new ArrayList<PromotionBenefit>();
        var independent = new ArrayList<PromotionBenefit>();
        var rateByPosition = new HashMap<Integer, Integer>();
        // A quote and its confirmation use the same order even when the catalog
        // or replay provides the same rates in a different iteration order.
        var orderedRates = quantityPrices.stream().sorted(Comparator.comparingInt(
                benefit -> benefit.affectedPositions().stream().mapToInt(Integer::intValue).min().orElseThrow())).toList();
        for (var benefit : orderedRates) {
            if (!benefit.quantityPrice() || benefit.payableAmount().signum() <= 0
                    || benefit.affectedPositions().isEmpty()) return null;
            boolean conflicts = false;
            for (var position : benefit.affectedPositions()) {
                if (!allPositions.contains(position) || !seenQuantityPositions.add(position)) return null;
                conflicts |= eligiblePositions.contains(position);
            }
            if (!conflicts) independent.add(benefit);
            else {
                int index = competing.size();
                competing.add(benefit);
                for (var position : benefit.affectedPositions()) rateByPosition.put(position, index);
            }
        }
        eligible.sort(Comparator.comparing(PromotionEvaluationLine::unitPrice).reversed()
                .thenComparingInt(PromotionEvaluationLine::position));
        var grouped = new LinkedHashMap<UUID, List<Row>>();
        for (var line : eligible) {
            var product = promotion.buyXPayYMode() == BuyXPayYMode.SAME_PRODUCT ? line.productId() : null;
            grouped.computeIfAbsent(product, ignored -> new ArrayList<>()).add(
                    new Row(line, whole(line.quantity()), rateByPosition.getOrDefault(line.position(), -1)));
        }
        var groups = new ArrayList<>(grouped.values());
        BitSet bestSelection;
        if (competing.size() <= MAX_COMPETING_RATES && ((long) eligible.size() << competing.size()) <= MAX_LINE_VISITS) {
            bestSelection = enumerateChoices(groups, size, competing);
        } else {
            bestSelection = OrderedRateChoices.choose(groups, size, competing);
            if (bestSelection == null) return null;
        }
        var benefits = new ArrayList<>(independent);
        for (int rate = 0; rate < competing.size(); rate++) {
            if (bestSelection.get(rate)) benefits.add(competing.get(rate));
        }
        materialize(promotion, groups, size, bestSelection, benefits);
        return PromotionPreview.priced(benefits);
    }

    private static BitSet enumerateChoices(List<List<Row>> groups, long size, List<PromotionBenefit> competing) {
        int selections = 1 << competing.size();
        BigDecimal best = BigDecimal.valueOf(-1);
        BitSet bestSelection = new BitSet();
        for (int selected = 0; selected < selections; selected++) {
            var mask = BitSet.valueOf(new long[] {selected});
            var saving = packSaving(groups, size, mask);
            for (int rate = 0; rate < competing.size(); rate++) {
                if ((selected & (1 << rate)) != 0) saving = saving.add(competing.get(rate).payableAmount());
            }
            if (saving.compareTo(best) > 0) {
                best = saving;
                bestSelection = mask;
            }
        }
        return bestSelection;
    }

    private static BigDecimal packSaving(List<List<Row>> groups, long size, BitSet selected) {
        var result = BigDecimal.ZERO;
        for (var group : groups) {
            long pending = 0;
            for (var row : group) {
                if (row.reserved(selected)) continue;
                long needed = size - pending;
                if (row.quantity() < needed) pending += row.quantity();
                else {
                    long completed = 1 + (row.quantity() - needed) / size;
                    result = result.add(row.line().unitPrice().multiply(BigDecimal.valueOf(completed)));
                    pending = (row.quantity() - needed) % size;
                }
            }
        }
        return result;
    }

    private static void materialize(Promotion promotion, List<List<Row>> groups, long size,
            BitSet selected, List<PromotionBenefit> benefits) {
        for (var group : groups) {
            long pending = 0;
            var positions = new LinkedHashSet<Integer>();
            for (var row : group) {
                if (row.reserved(selected)) continue;
                long remaining = row.quantity();
                if (pending > 0) {
                    long used = Math.min(size - pending, remaining);
                    positions.add(row.line().position());
                    pending += used;
                    remaining -= used;
                    if (pending == size) {
                        addBenefit(promotion, row.line(), 1, positions, benefits);
                        positions.clear();
                        pending = 0;
                    }
                }
                long complete = remaining / size;
                if (complete > 0) addBenefit(promotion, row.line(), complete,
                        Set.of(row.line().position()), benefits);
                remaining %= size;
                if (remaining > 0) {
                    positions.add(row.line().position());
                    pending = remaining;
                }
            }
        }
    }

    private static void addBenefit(Promotion promotion, PromotionEvaluationLine freeLine, long count,
            Set<Integer> positions, List<PromotionBenefit> result) {
        if (freeLine.unitPrice().signum() == 0) return;
        var benefit = PromotionEngine.benefit(promotion, positions, freeLine.unitPrice(),
                PromotionEngine.TaxKey.of(freeLine));
        // Preserve the fiscal rounding of each original pack when batching.
        result.add(MixedBasketPricing.repeated(benefit, count, positions));
    }

    private static long whole(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > 0
                || value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) return -1;
        return value.longValueExact();
    }

    record Row(PromotionEvaluationLine line, long quantity, int quantityRate) {
        boolean reserved(BitSet selected) {
            return quantityRate >= 0 && selected.get(quantityRate);
        }
    }
}

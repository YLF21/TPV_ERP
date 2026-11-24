package com.tpverp.backend.promotion;

import static com.tpverp.backend.promotion.PromotionEngine.euros;

import com.tpverp.backend.promotion.PromotionEngine.TaxKey;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Exact allocation for tickets with a quantity price. A pack consumes counts of
 * interchangeable units, so neither its generation nor the solver needs unit IDs. */
final class MixedBasketPricing {
    private final List<Resource> resources = new ArrayList<>();
    private final Map<Integer, Integer> resourceByPosition = new HashMap<>();
    private final List<Choice> choices = new ArrayList<>();

    static PromotionPreview preview(PromotionEvaluationRequest request, List<PromotionBenefit> quantityPrices) {
        var promotions = request.promotions().stream()
                .filter(promotion -> promotion.type() != PromotionType.PURCHASE_THRESHOLD_COUPON)
                .sorted(Comparator.comparing(Promotion::name).thenComparing(Promotion::id)).toList();
        if (promotions.isEmpty()) return PromotionPreview.priced(quantityPrices);

        var ordered = OrderedBuyPayPricing.tryPreview(request, quantityPrices);
        if (ordered != null) return ordered;

        var pricing = new MixedBasketPricing();
        var eligible = promotions.stream().map(promotion ->
                PromotionEligibility.eligibleLines(promotion, request.lines(), request.targets())).toList();
        pricing.indexLines(request.lines(), promotions, eligible, quantityPrices);
        for (var price : quantityPrices) pricing.addWhole(price.affectedPositions(), List.of(price));
        for (int i = 0; i < eligible.size(); i++) pricing.addPromotion(promotions.get(i), eligible.get(i));
        return pricing.solve();
    }

    private void indexLines(List<PromotionEvaluationLine> lines, List<Promotion> promotions,
                            List<List<PromotionEvaluationLine>> eligible, List<PromotionBenefit> quantityPrices) {
        var membership = new HashMap<Integer, BitSet>();
        var restricted = new java.util.HashSet<Integer>();
        for (int index = 0; index < eligible.size(); index++) {
            var promotion = promotions.get(index);
            boolean sameProduct = promotion.type() == PromotionType.BUY_X_PAY_Y
                    && promotion.buyXPayYMode() == BuyXPayYMode.SAME_PRODUCT;
            for (var line : eligible.get(index)) {
                membership.computeIfAbsent(line.position(), ignored -> new BitSet()).set(index);
                if (sameProduct) restricted.add(line.position());
            }
        }
        var quantityGroup = new HashMap<Integer, Integer>();
        for (int i = 0; i < quantityPrices.size(); i++) {
            for (var position : quantityPrices.get(i).affectedPositions()) quantityGroup.put(position, i);
        }
        var index = new LinkedHashMap<Signature, Resource>();
        for (var line : lines.stream().sorted(Comparator.comparingInt(PromotionEvaluationLine::position)).toList()) {
            if (!membership.containsKey(line.position()) && !quantityGroup.containsKey(line.position())) continue;
            boolean whole = line.quantity().stripTrailingZeros().scale() <= 0;
            // Fractional product lines participate in quantity rates and basket thresholds,
            // but the classic per-unit deals require an integral quantity on the source line.
            long count = whole ? line.quantity().longValueExact() : 1;
            var signature = new Signature(membership.get(line.position()), quantityGroup.get(line.position()),
                    restricted.contains(line.position()) ? line.productId() : null, whole,
                    TaxKey.of(line), line.unitPrice().stripTrailingZeros(), line.payableUnitAmount().stripTrailingZeros());
            var resource = index.get(signature);
            if (resource == null || Long.MAX_VALUE - resource.capacity < count) {
                resource = new Resource(resources.size(), signature);
                index.put(signature, resource);
                resources.add(resource);
            }
            resource.capacity += count;
            resource.lines.add(new Remainder(line.position(), count));
            resourceByPosition.put(line.position(), resource.index);
        }
    }

    private void addPromotion(Promotion promotion, List<PromotionEvaluationLine> lines) {
        if (lines.isEmpty()) return;
        var positions = lines.stream().map(PromotionEvaluationLine::position).toList();
        if (promotion.type() == PromotionType.QUANTITY_DISCOUNT || promotion.type() == PromotionType.PURCHASE_THRESHOLD_DISCOUNT) {
            boolean monetary = promotion.type() == PromotionType.PURCHASE_THRESHOLD_DISCOUNT;
            var benefits = PromotionEngine.thresholdBenefits(promotion, lines,
                    monetary ? promotion.minimumAmount() : null, monetary ? null : promotion.minimumQuantity());
            if (!benefits.isEmpty()) addWhole(positions, benefits);
            return;
        }
        boolean buyPay = promotion.type() == PromotionType.BUY_X_PAY_Y;
        boolean secondUnit = promotion.type() == PromotionType.SECOND_UNIT_PERCENT;
        long size = secondUnit ? 2 : whole(promotion.buyQuantity());
        long pay = buyPay ? whole(promotion.payQuantity()) : 0;
        if (size <= 0 || pay < 0 || pay >= size) return;
        if (secondUnit && (promotion.discountPercent() == null || promotion.discountPercent().signum() <= 0)) return;
        if (promotion.type() == PromotionType.FIXED_PACK_PRICE && promotion.packPrice() == null) return;

        var groups = new LinkedHashMap<PackGroup, List<Resource>>();
        for (var resource : resourceIds(positions)) {
            var item = resources.get(resource);
            if (!item.signature.whole()) continue;
            var key = new PackGroup(buyPay ? null : item.signature.tax(),
                    buyPay && promotion.buyXPayYMode() == BuyXPayYMode.SAME_PRODUCT ? item.signature.product() : null);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
        }
        for (var group : groups.values()) {
            long[] capacity = group.stream().mapToLong(item -> item.capacity).toArray();
            combinations(size, capacity, counts -> addPack(promotion, group, counts, size - pay));
        }
    }

    private int[] resourceIds(Collection<Integer> positions) {
        return positions.stream().mapToInt(resourceByPosition::get).distinct().sorted().toArray();
    }

    private static long whole(BigDecimal quantity) {
        if (quantity == null || quantity.signum() < 0 || quantity.stripTrailingZeros().scale() > 0) return -1;
        return quantity.longValueExact();
    }

    private void addWhole(Collection<Integer> positions, List<PromotionBenefit> benefits) {
        int[] ids = resourceIds(positions);
        long[] counts = Arrays.stream(ids).mapToLong(index -> resources.get(index).capacity).toArray();
        addChoice(ids, counts, 1, benefits, true);
    }

    private void addPack(Promotion promotion, List<Resource> group, long[] counts, long free) {
        var parts = new ArrayList<Part>();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) parts.add(new Part(group.get(i), counts[i]));
        }
        var benefits = packBenefits(promotion, parts, free);
        if (benefits.isEmpty()) return;
        int[] ids = parts.stream().mapToInt(part -> part.resource.index).toArray();
        long[] units = parts.stream().mapToLong(Part::count).toArray();
        long limit = parts.stream().mapToLong(part -> part.resource.capacity / part.count).min().orElseThrow();
        addChoice(ids, units, limit, benefits, false);
    }

    private void addChoice(int[] ids, long[] counts, long limit, List<PromotionBenefit> benefits, boolean whole) {
        var saving = payable(benefits);
        if (saving.signum() > 0) choices.add(new Choice(
                new PromotionCapacityOptimizer.Option(ids, counts, limit, saving), benefits, whole));
    }

    /** Enumerate bounded integer compositions, once per distinct economic pack.
     * Suffix capacities exclude only branches which cannot fill the pack. */
    private static void combinations(long size, long[] capacity, Consumer<long[]> accept) {
        int n = capacity.length;
        long[] suffix = new long[n + 1];
        for (int i = n - 1; i >= 0; i--) suffix[i] = suffix[i+1] + Math.min(size - suffix[i+1], capacity[i]);
        if (n == 0 || suffix[0] < size) return;
        long[] left = new long[n], take = new long[n], minimum = new long[n];
        int i = 0;
        left[0] = size;
        take[0] = Math.min(capacity[0], size);
        minimum[0] = size - suffix[1];
        while (i >= 0) {
            if (take[i] < minimum[i]) {
                if (--i >= 0) take[i]--;
            } else if (left[i] == take[i]) {
                Arrays.fill(take, i + 1, n, 0);
                accept.accept(take);
                take[i]--;
            } else {
                long needed = left[i] - take[i];
                i++;
                left[i] = needed;
                take[i] = Math.min(needed, capacity[i]);
                minimum[i] = Math.max(0, needed - suffix[i+1]);
            }
        }
    }

    private static List<PromotionBenefit> packBenefits(Promotion promotion, List<Part> parts, long free) {
        if (promotion.type() == PromotionType.BUY_X_PAY_Y) return freeUnitBenefits(promotion, parts, free);
        BigDecimal amount;
        if (promotion.type() == PromotionType.SECOND_UNIT_PERCENT) {
            var cheaper = parts.stream().map(part -> part.resource.signature.price()).min(BigDecimal::compareTo).orElseThrow();
            amount = cheaper.multiply(promotion.discountPercent()).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        } else {
            amount = parts.stream().map(part -> part.resource.signature.price().multiply(BigDecimal.valueOf(part.count)))
                    .reduce(BigDecimal.ZERO, BigDecimal::add).subtract(promotion.packPrice());
        }
        return euros(amount).signum() > 0
                ? List.of(PromotionEngine.benefit(promotion, Set.of(), amount, parts.getFirst().resource.signature.tax()))
                : List.of();
    }

    private static List<PromotionBenefit> freeUnitBenefits(Promotion promotion, List<Part> parts, long free) {
        var sorted = parts.stream().sorted(Comparator.comparing(part -> part.resource.signature.payable())).toList();
        var amounts = new LinkedHashMap<TaxKey, BigDecimal>();
        for (int start = 0; start < sorted.size() && free > 0;) {
            int end = start;
            long count = 0;
            var price = sorted.get(start).resource.signature.payable();
            while (end < sorted.size() && sorted.get(end).resource.signature.payable().compareTo(price) == 0) {
                count += sorted.get(end++).count;
            }
            var tied = sorted.subList(start, end);
            if (free < count && hasDifferentFiscalValues(tied)) {
                // Equal final unit prices can have different tax rates. Compare legal
                // choices at the cutoff after rounding each tax adjustment to cents.
                var best = new BestFreeUnits();
                combinations(free, tied.stream().mapToLong(Part::count).toArray(), chosen -> {
                    var trial = new LinkedHashMap<>(amounts);
                    for (int i = 0; i < chosen.length; i++) addFree(trial, tied.get(i), chosen[i]);
                    var benefits = taxBenefits(promotion, trial);
                    var saving = payable(benefits);
                    if (best.saving.compareTo(saving) < 0) {
                        best.saving = saving;
                        best.benefits = benefits;
                    }
                });
                return best.benefits;
            }
            for (var part : tied) {
                long take = Math.min(part.count, free);
                addFree(amounts, part, take);
                free -= take;
            }
            start = end;
        }
        return taxBenefits(promotion, amounts);
    }

    private static boolean hasDifferentFiscalValues(List<Part> tied) {
        var first = tied.getFirst().resource.signature;
        return tied.stream().anyMatch(part -> !first.tax().equals(part.resource.signature.tax())
                || first.price().compareTo(part.resource.signature.price()) != 0);
    }

    private static void addFree(Map<TaxKey, BigDecimal> amounts, Part part, long count) {
        if (count > 0) amounts.merge(part.resource.signature.tax(),
                part.resource.signature.price().multiply(BigDecimal.valueOf(count)), BigDecimal::add);
    }

    private static List<PromotionBenefit> taxBenefits(Promotion promotion, Map<TaxKey, BigDecimal> amounts) {
        return amounts.entrySet().stream().filter(entry -> euros(entry.getValue()).signum() > 0)
                .map(entry -> PromotionEngine.benefit(promotion, Set.of(), entry.getValue(), entry.getKey())).toList();
    }

    private static BigDecimal payable(List<PromotionBenefit> benefits) {
        return benefits.stream().map(PromotionBenefit::payableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private PromotionPreview solve() {
        long[] capacity = resources.stream().mapToLong(resource -> resource.capacity).toArray();
        long[] selected = PromotionCapacityOptimizer.maximize(capacity, choices.stream().map(Choice::option).toList());
        var benefits = new ArrayList<PromotionBenefit>();
        for (int i = 0; i < selected.length; i++) {
            if (selected[i] == 0) continue;
            var choice = choices.get(i);
            if (choice.whole) benefits.addAll(choice.benefits);
            else materializePacks(choice, selected[i], benefits);
        }
        return PromotionPreview.priced(benefits);
    }

    /** Batch equal applications while preserving the exact positions and the tax
     * rounded per original pack. A run of 50,000 packs on one line is one adjustment. */
    private void materializePacks(Choice choice, long uses, List<PromotionBenefit> result) {
        var option = choice.option;
        var ids = option.resources();
        var units = option.quantities();
        while (uses > 0) {
            long batch = uses;
            for (int j = 0; j < ids.length; j++) {
                long available = resources.get(ids[j]).lines.getFirst().count / units[j];
                batch = Math.min(batch, Math.max(1, available));
            }
            var positions = new LinkedHashSet<Integer>();
            for (int j = 0; j < ids.length; j++) {
                var queue = resources.get(ids[j]).lines;
                long needed = Math.multiplyExact(units[j], batch);
                while (needed > 0) {
                    var line = queue.getFirst();
                    var consumed = Math.min(needed, line.count);
                    positions.add(line.position);
                    needed -= consumed;
                    line.count -= consumed;
                    if (line.count == 0) queue.removeFirst();
                }
            }
            for (var benefit : choice.benefits) result.add(repeated(benefit, batch, positions));
            uses -= batch;
        }
    }

    static PromotionBenefit repeated(PromotionBenefit benefit, long count, Set<Integer> positions) {
        var tax = new TaxKey(benefit.taxIncluded(), benefit.taxRegime(), benefit.taxPercent());
        var amounts = PromotionEngine.quantityAmounts(benefit.amount(), tax);
        var n = BigDecimal.valueOf(count);
        return new PromotionBenefit(benefit.promotionId(), benefit.promotionVersionId(), benefit.name(),
                positions, benefit.amount().multiply(n), tax.included(), tax.regime(), tax.percent(), false,
                new PromotionBenefit.QuantityAmounts(amounts.base().multiply(n), amounts.tax().multiply(n), amounts.total().multiply(n)));
    }

    private record Signature(BitSet eligibility, Integer quantityGroup, UUID product, boolean whole,
                             TaxKey tax, BigDecimal price, BigDecimal payable) {}
    private record PackGroup(TaxKey tax, UUID product) {}
    private record Part(Resource resource, long count) {}
    private record Choice(PromotionCapacityOptimizer.Option option, List<PromotionBenefit> benefits, boolean whole) {}

    private static final class BestFreeUnits {
        BigDecimal saving = BigDecimal.ZERO;
        List<PromotionBenefit> benefits = List.of();
    }

    private static final class Resource {
        final int index;
        final Signature signature;
        final ArrayDeque<Remainder> lines = new ArrayDeque<>();
        long capacity;
        Resource(int index, Signature signature) { this.index = index; this.signature = signature; }
    }

    private static final class Remainder {
        final int position;
        long count;
        Remainder(int position, long count) { this.position = position; this.count = count; }
    }
}

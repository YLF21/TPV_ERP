package com.tpverp.backend.promotion;

import com.tpverp.backend.catalog.QuantityPricing;
import com.tpverp.backend.document.Money;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Exact totals, without a rounded average unit price or expansion into individual units. */
public final class QuantityPricingCalculator {

    private QuantityPricingCalculator() {
    }

    public static BigDecimal total(QuantityPricing rule, BigDecimal salePrice, BigDecimal quantity) {
        return calculate(rule, salePrice, quantity).total();
    }

    public record AppliedTier(int quantity, BigDecimal price, BigInteger count) {
    }

    public record Calculation(BigDecimal total, QuantityPricing.Mode mode, List<AppliedTier> appliedTiers,
            BigDecimal regularQuantity) {
        public String description() {
            if (mode == QuantityPricing.Mode.UNIT_THRESHOLD && !appliedTiers.isEmpty()) {
                var tier = appliedTiers.getFirst();
                return "PRECIO POR CANTIDAD: desde " + tier.quantity() + " uds, "
                        + tier.price().toPlainString() + "/ud";
            }
            var parts = appliedTiers.stream().map(tier -> tier.count() + " lote(s) de "
                    + tier.quantity() + " uds a " + tier.price().toPlainString()).toList();
            var result = "PRECIO POR CANTIDAD: " + String.join(" + ", parts);
            if (regularQuantity.signum() > 0) {
                result += " + " + regularQuantity.stripTrailingZeros().toPlainString() + " uds PVP";
            }
            // Document descriptions have a 255-character storage contract.
            return result.length() <= 255 ? result : result.substring(0, 252) + "...";
        }
    }

    public static Calculation calculate(QuantityPricing rule, BigDecimal salePrice, BigDecimal quantity) {
        if (quantity.signum() <= 0) return new Calculation(Money.euros(BigDecimal.ZERO), null, List.of(), quantity);
        var regular = salePrice.multiply(quantity);
        if (rule == null || !rule.enabled()) return new Calculation(Money.euros(regular), null, List.of(), quantity);
        if (rule.mode() == QuantityPricing.Mode.UNIT_THRESHOLD) {
            var selected = rule.tiers().stream()
                    .filter(tier -> quantity.compareTo(BigDecimal.valueOf(tier.quantity())) >= 0)
                    .filter(tier -> tier.price().compareTo(salePrice) < 0)
                    .min(Comparator.comparing(QuantityPricing.Tier::price)
                            .thenComparing(QuantityPricing.Tier::quantity, Comparator.reverseOrder()));
            return selected.map(tier -> new Calculation(Money.euros(tier.price().multiply(quantity)), rule.mode(),
                    List.of(new AppliedTier(tier.quantity(), tier.price(), BigInteger.ONE)), BigDecimal.ZERO))
                    .orElseGet(() -> new Calculation(Money.euros(regular), rule.mode(), List.of(), quantity));
        }
        var units = quantity.toBigInteger();
        var packs = new ArrayList<Pack>();
        for (var tier : rule.tiers()) {
            var size = BigInteger.valueOf(tier.quantity());
            if (size.compareTo(units) <= 0
                    && tier.price().compareTo(salePrice.multiply(new BigDecimal(size))) < 0) {
                packs.add(new Pack(size, tier.price()));
            }
        }
        if (packs.isEmpty()) return new Calculation(Money.euros(regular), rule.mode(), List.of(), quantity);
        packs.sort((left, right) -> {
            var ratio = left.price().multiply(new BigDecimal(right.quantity()))
                    .compareTo(right.price().multiply(new BigDecimal(left.quantity())));
            return ratio != 0 ? ratio : right.quantity().compareTo(left.quantity());
        });
        var optimizer = new PackOptimizer(packs, salePrice, units);
        optimizer.search(0, units, BigDecimal.ZERO, new BigInteger[packs.size()]);
        var fractional = quantity.subtract(new BigDecimal(units)).multiply(salePrice);
        var applied = new ArrayList<AppliedTier>();
        var packedQuantity = BigInteger.ZERO;
        for (int index = 0; index < packs.size(); index++) {
            var count = optimizer.bestCounts[index];
            if (count == null || count.signum() == 0) continue;
            var pack = packs.get(index);
            applied.add(new AppliedTier(pack.quantity().intValueExact(), pack.price(), count));
            packedQuantity = packedQuantity.add(pack.quantity().multiply(count));
        }
        return new Calculation(Money.euros(optimizer.best.add(fractional)), rule.mode(), List.copyOf(applied),
                quantity.subtract(new BigDecimal(packedQuantity)));
    }

    private record Pack(BigInteger quantity, BigDecimal price) {
    }

    /** Branch and bound on pack counts. Bounds compare exact rational prices by cross multiplication. */
    private static final class PackOptimizer {
        private final List<Pack> packs;
        private final BigDecimal salePrice;
        private final BigInteger[] remainingGcd;
        private BigDecimal best;
        private BigInteger[] bestCounts;

        private PackOptimizer(List<Pack> packs, BigDecimal salePrice, BigInteger quantity) {
            this.packs = packs;
            this.salePrice = salePrice;
            remainingGcd = new BigInteger[packs.size()];
            for (int index = packs.size() - 1; index >= 0; index--) {
                remainingGcd[index] = index == packs.size() - 1 ? packs.get(index).quantity()
                        : packs.get(index).quantity().gcd(remainingGcd[index + 1]);
            }
            bestCounts = new BigInteger[packs.size()];
            best = salePrice.multiply(new BigDecimal(quantity));
            for (int index = 0; index < packs.size(); index++) {
                var pack = packs.get(index);
                var division = quantity.divideAndRemainder(pack.quantity());
                var candidate = pack.price().multiply(new BigDecimal(division[0]))
                        .add(salePrice.multiply(new BigDecimal(division[1])));
                if (candidate.compareTo(best) < 0) {
                    best = candidate;
                    bestCounts = new BigInteger[packs.size()];
                    bestCounts[index] = division[0];
                }
            }
        }

        private void improve(BigDecimal candidate, BigInteger[] counts) {
            if (candidate.compareTo(best) < 0) {
                best = candidate;
                bestCounts = counts.clone();
            }
        }

        private void search(int index, BigInteger quantity, BigDecimal spent, BigInteger[] counts) {
            if (index == packs.size()) {
                improve(spent.add(salePrice.multiply(new BigDecimal(quantity))), counts);
                return;
            }
            var pack = packs.get(index);
            // Every remaining pack consumes a multiple of this gcd, so at least
            // this remainder must be bought at list price. Including it prevents
            // equal-rate packs from enumerating billions of equivalent counts.
            var regularRemainder = quantity.remainder(remainingGcd[index]);
            var lowerBound = spent.add(salePrice.multiply(new BigDecimal(regularRemainder)))
                    .multiply(new BigDecimal(pack.quantity()))
                    .add(pack.price().multiply(new BigDecimal(quantity.subtract(regularRemainder))));
            if (lowerBound.compareTo(best.multiply(new BigDecimal(pack.quantity()))) >= 0) return;
            if (index == packs.size() - 1) {
                var division = quantity.divideAndRemainder(pack.quantity());
                counts[index] = division[0];
                improve(spent.add(pack.price().multiply(new BigDecimal(division[0])))
                        .add(salePrice.multiply(new BigDecimal(division[1]))), counts);
                return;
            }
            var next = packs.get(index + 1);
            for (var count = quantity.divide(pack.quantity()); count.signum() >= 0;
                    count = count.subtract(BigInteger.ONE)) {
                var remaining = quantity.subtract(count.multiply(pack.quantity()));
                var subtotal = spent.add(pack.price().multiply(new BigDecimal(count)));
                // The bound only increases as fewer of this cheaper pack are selected.
                if (subtotal.multiply(new BigDecimal(next.quantity()))
                        .add(next.price().multiply(new BigDecimal(remaining)))
                        .compareTo(best.multiply(new BigDecimal(next.quantity()))) >= 0) break;
                counts[index] = count;
                search(index + 1, remaining, subtotal, counts);
                // A mixed solution can reach the node's bound even when none of
                // the initial single-size solutions did. Stop as soon as it does.
                if (lowerBound.compareTo(best.multiply(new BigDecimal(pack.quantity()))) >= 0) return;
            }
        }
    }
}

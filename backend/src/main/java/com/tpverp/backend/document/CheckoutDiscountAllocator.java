package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Materializes the fixed checkout discount as hidden fiscal lines. The customer-facing
 * amount is distributed only after line discounts, promotions and coupons are known.
 */
final class CheckoutDiscountAllocator {

    private static final Comparator<TaxKey> TAX_ORDER = Comparator
            .comparing(TaxKey::regime)
            .thenComparing(TaxKey::percentage);

    private CheckoutDiscountAllocator() {
    }

    static void apply(CommercialDocument document, BigDecimal requestedDiscount) {
        apply(document, requestedDiscount, null);
    }

    static void apply(
            CommercialDocument document,
            BigDecimal requestedDiscount,
            Set<UUID> eligibleProductIds) {
        applyReduction(
                document,
                requestedDiscount,
                eligibleProductIds,
                DocumentLineType.MANUAL_DISCOUNT,
                "DESCUENTO",
                false,
                "checkout_discount");
    }

    static void applyMemberBalance(
            CommercialDocument document,
            BigDecimal requestedAmount,
            Set<UUID> eligibleProductIds) {
        applyReduction(
                document,
                requestedAmount,
                eligibleProductIds,
                DocumentLineType.MEMBER_BALANCE,
                "SALDO DE MIEMBRO",
                true,
                "member_balance");
    }

    private static void applyReduction(
            CommercialDocument document,
            BigDecimal requestedDiscount,
            Set<UUID> eligibleProductIds,
            DocumentLineType lineType,
            String description,
            boolean allowZeroTotal,
            String errorPrefix) {
        if (requestedDiscount == null || requestedDiscount.signum() == 0) {
            return;
        }
        var discount = Money.euros(requestedDiscount);
        if (discount.signum() <= 0
                || discount.compareTo(document.getTotal()) > 0
                || (!allowZeroTotal && discount.compareTo(document.getTotal()) == 0)) {
            throw new IllegalArgumentException(errorPrefix + "_exceeds_total");
        }
        var totalBeforeDiscount = document.getTotal();

        var fiscalWeights = finalPositiveSaleTotals(document, eligibleProductIds);
        if (fiscalWeights.isEmpty()) {
            throw new IllegalStateException(errorPrefix + "_without_eligible_lines");
        }
        var eligibleTotal = Money.euros(fiscalWeights.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        if (discount.compareTo(eligibleTotal) > 0) {
            throw new IllegalArgumentException(errorPrefix + "_exceeds_eligible_total");
        }
        var allocations = Money.allocateByLargestRemainder(
                discount, fiscalWeights.values().stream().toList());
        var position = document.getLineas().stream()
                .mapToInt(DocumentLine::getPosicion)
                .max().orElse(0) + 1;
        var index = 0;
        for (var entry : fiscalWeights.entrySet()) {
            var allocated = allocations.get(index++);
            if (allocated.signum() == 0) {
                continue;
            }
            var key = entry.getKey();
            // Both F10 and F11 are tax-included reductions. Persisting one fiscal line
            // per tax group keeps the accepted amount exact for mixed tax rates.
            document.addLine(DocumentLine.special(
                    document,
                    position++,
                    description,
                    allocated.negate(),
                    true,
                    key.regime(),
                    key.percentage(),
                    null,
                    null,
                    null,
                    lineType));
        }
        var expectedTotal = Money.euros(totalBeforeDiscount.subtract(discount));
        if (document.getTotal().compareTo(expectedTotal) != 0
                || document.getBaseTotal().add(document.getImpuestoTotal())
                        .compareTo(document.getTotal()) != 0) {
            throw new IllegalStateException(errorPrefix + "_fiscal_mismatch");
        }
    }

    private static Map<TaxKey, BigDecimal> finalPositiveSaleTotals(
            CommercialDocument document,
            Set<UUID> eligibleProductIds) {
        var totals = new TreeMap<TaxKey, BigDecimal>(TAX_ORDER);
        document.getLineas().stream()
                .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                .filter(line -> belongsToCurrentPositiveSale(line, eligibleProductIds))
                .forEach(line -> totals.merge(
                        new TaxKey(line.getRegimenImpuesto(), line.getPorcentajeImpuesto()),
                        line.getTotal(),
                        BigDecimal::add));
        totals.entrySet().removeIf(entry -> entry.getValue().signum() <= 0);
        totals.replaceAll((ignored, value) -> Money.euros(value));
        return totals;
    }

    private static boolean belongsToCurrentPositiveSale(
            DocumentLine line,
            Set<UUID> eligibleProductIds) {
        if (line.getLineType() == DocumentLineType.PRODUCT) {
            return line.getTotal().signum() > 0
                    && (eligibleProductIds == null
                            || eligibleProductIds.contains(line.getProductoId()));
        }
        return (line.getLineType() == DocumentLineType.PROMOTION
                || line.getLineType() == DocumentLineType.PROMOTIONAL_COUPON
                || line.getLineType() == DocumentLineType.DOCUMENT_DISCOUNT
                || line.getLineType() == DocumentLineType.MEMBER_BALANCE)
                && line.getTotal().signum() < 0;
    }

    private record TaxKey(String regime, BigDecimal percentage) {
    }
}

package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

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
        if (requestedDiscount == null || requestedDiscount.signum() == 0) {
            return;
        }
        var discount = Money.euros(requestedDiscount);
        if (discount.signum() <= 0 || discount.compareTo(document.getTotal()) >= 0) {
            throw new IllegalArgumentException("checkout_discount_exceeds_total");
        }
        var totalBeforeDiscount = document.getTotal();

        var fiscalWeights = finalPositiveSaleTotals(document);
        if (fiscalWeights.isEmpty()) {
            throw new IllegalStateException("checkout_discount_without_eligible_lines");
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
            // F11 is a tax-included amount paid by the customer. Storing the adjustment
            // as tax included guarantees that its persisted total is exactly the amount
            // allocated, also for products whose catalogue price excludes tax.
            document.addLine(DocumentLine.manualDiscount(
                    document,
                    position++,
                    allocated.negate(),
                    true,
                    key.regime(),
                    key.percentage()));
        }
        var expectedTotal = Money.euros(totalBeforeDiscount.subtract(discount));
        if (document.getTotal().compareTo(expectedTotal) != 0
                || document.getBaseTotal().add(document.getImpuestoTotal())
                        .compareTo(document.getTotal()) != 0) {
            throw new IllegalStateException("checkout_discount_fiscal_mismatch");
        }
    }

    private static Map<TaxKey, BigDecimal> finalPositiveSaleTotals(
            CommercialDocument document) {
        var totals = new TreeMap<TaxKey, BigDecimal>(TAX_ORDER);
        document.getLineas().stream()
                .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                .filter(CheckoutDiscountAllocator::belongsToCurrentPositiveSale)
                .forEach(line -> totals.merge(
                        new TaxKey(line.getRegimenImpuesto(), line.getPorcentajeImpuesto()),
                        line.getTotal(),
                        BigDecimal::add));
        totals.entrySet().removeIf(entry -> entry.getValue().signum() <= 0);
        totals.replaceAll((ignored, value) -> Money.euros(value));
        return totals;
    }

    private static boolean belongsToCurrentPositiveSale(DocumentLine line) {
        if (line.getLineType() == DocumentLineType.PRODUCT) {
            return line.getTotal().signum() > 0;
        }
        return (line.getLineType() == DocumentLineType.PROMOTION
                || line.getLineType() == DocumentLineType.PROMOTIONAL_COUPON)
                && line.getTotal().signum() < 0;
    }

    private record TaxKey(String regime, BigDecimal percentage) {
    }
}

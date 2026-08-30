package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Materializes Ctrl+/ or a member percentage over the final eligible fiscal net. */
final class DocumentPercentDiscountAllocator {

    private static final Comparator<TaxKey> TAX_ORDER = Comparator
            .comparing(TaxKey::regime)
            .thenComparing(TaxKey::percentage);

    private DocumentPercentDiscountAllocator() {
    }

    static BigDecimal apply(
            CommercialDocument document,
            BigDecimal requestedPercent,
            Set<UUID> eligibleProductIds) {
        if (requestedPercent == null || requestedPercent.signum() == 0) {
            return Money.euros(BigDecimal.ZERO);
        }
        var percent = Money.validPercentage(requestedPercent);
        var fiscalNets = finalEligibleFiscalNets(document, eligibleProductIds);
        if (fiscalNets.isEmpty()) {
            throw new IllegalStateException("document_discount_without_eligible_lines");
        }
        var total = Money.euros(fiscalNets.values().stream()
                .map(FiscalNet::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        var discount = Money.euros(total.multiply(percent).movePointLeft(2));
        if (discount.signum() == 0) {
            return total;
        }
        var allocations = Money.allocateByLargestRemainder(
                discount,
                fiscalNets.values().stream().map(FiscalNet::total).toList());
        var position = document.getLineas().stream()
                .mapToInt(DocumentLine::getPosicion)
                .max()
                .orElse(0) + 1;
        var index = 0;
        for (var entry : fiscalNets.entrySet()) {
            var amount = allocations.get(index++);
            if (amount.signum() == 0) {
                continue;
            }
            var key = entry.getKey();
            var source = entry.getValue().source();
            var adjustment = DocumentLine.special(
                    document,
                    position++,
                    "DESCUENTO DOCUMENTAL",
                    amount.negate(),
                    true,
                    key.regime(),
                    key.percentage(),
                    null,
                    null,
                    null,
                    DocumentLineType.DOCUMENT_DISCOUNT);
            // V185 requires every fiscal discount line to retain a real source line.
            adjustment.linkDocumentAdjustment(null, source.getId());
            document.addLine(adjustment);
        }
        return total;
    }

    static Map<TaxKey, FiscalNet> finalEligibleFiscalNets(
            CommercialDocument document,
            Set<UUID> eligibleProductIds) {
        var groups = new TreeMap<TaxKey, FiscalNet>(TAX_ORDER);
        var eligiblePositions = new java.util.HashSet<Integer>();
        document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                .filter(line -> line.getTotal().signum() > 0)
                .filter(line -> eligibleProductIds == null
                        || eligibleProductIds.contains(line.getProductoId()))
                .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                .forEach(line -> {
                    eligiblePositions.add(line.getPosicion());
                    var key = TaxKey.from(line);
                    groups.merge(
                            key,
                            new FiscalNet(line.getTotal(), line),
                            FiscalNet::merge);
                });

        document.getLineas().stream()
                .filter(line -> line.getTotal().signum() < 0)
                .filter(line -> line.getLineType() == DocumentLineType.PROMOTION
                        || line.getLineType() == DocumentLineType.PROMOTIONAL_COUPON)
                .filter(line -> line.getLineType() != DocumentLineType.PROMOTION
                        || line.getPromotionAffectedPositions().isEmpty()
                        || line.getPromotionAffectedPositions().stream()
                                .anyMatch(eligiblePositions::contains))
                .forEach(line -> {
                    var key = TaxKey.from(line);
                    var current = groups.get(key);
                    if (current != null) {
                        groups.put(key, current.withTotal(
                                current.total().add(line.getTotal())));
                    }
                });

        groups.entrySet().removeIf(entry -> entry.getValue().total().signum() <= 0);
        groups.replaceAll((ignored, value) -> value.withTotal(Money.euros(value.total())));
        return groups;
    }

    record TaxKey(String regime, BigDecimal percentage) {
        static TaxKey from(DocumentLine line) {
            return new TaxKey(line.getRegimenImpuesto(), line.getPorcentajeImpuesto());
        }
    }

    record FiscalNet(BigDecimal total, DocumentLine source) {
        FiscalNet merge(FiscalNet other) {
            return new FiscalNet(total.add(other.total), source);
        }

        FiscalNet withTotal(BigDecimal value) {
            return new FiscalNet(value, source);
        }
    }
}

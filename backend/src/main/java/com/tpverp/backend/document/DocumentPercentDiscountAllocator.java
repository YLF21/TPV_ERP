package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;

/** Materializes the Ctrl+/ or member percentage after promotions and coupons. */
final class DocumentPercentDiscountAllocator {

    private DocumentPercentDiscountAllocator() {
    }

    static void apply(
            CommercialDocument document,
            BigDecimal requestedPercent,
            Set<UUID> eligibleProductIds) {
        if (requestedPercent == null || requestedPercent.signum() == 0) return;
        var percent = Money.validPercentage(requestedPercent);
        var eligible = document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                .filter(line -> line.getTotal().signum() > 0)
                .filter(line -> eligibleProductIds == null || eligibleProductIds.contains(line.getProductoId()))
                .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                .toList();
        if (eligible.isEmpty()) throw new IllegalStateException("document_discount_without_eligible_lines");
        var productTotal = eligible.stream().map(DocumentLine::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Promotions and coupons are already materialized at this point. Their
        // negative fiscal lines reduce the document net before the percentage.
        var precedingAdjustments = document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PROMOTION
                        || line.getLineType() == DocumentLineType.PROMOTIONAL_COUPON)
                .map(DocumentLine::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var total = productTotal.add(precedingAdjustments).max(BigDecimal.ZERO);
        var discount = Money.euros(total.multiply(percent).movePointLeft(2));
        if (discount.signum() == 0) return;
        var allocations = Money.allocateByLargestRemainder(
                discount, eligible.stream().map(DocumentLine::getTotal).toList());
        var position = document.getLineas().stream().mapToInt(DocumentLine::getPosicion).max().orElse(0) + 1;
        for (int i = 0; i < eligible.size(); i++) {
            var amount = allocations.get(i);
            if (amount.signum() == 0) continue;
            var source = eligible.get(i);
            var adjustment = DocumentLine.special(
                    document, position++, "DESCUENTO DOCUMENTAL", amount.negate(),
                    source.isImpuestosIncluidos(), source.getRegimenImpuesto(),
                    source.getPorcentajeImpuesto(), null, null, null,
                    DocumentLineType.DOCUMENT_DISCOUNT);
            adjustment.linkDocumentAdjustment(null, source.getId());
            document.addLine(adjustment);
        }
    }
}

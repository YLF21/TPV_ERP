package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Creates a persisted member adjustment without going through service collaborators. */
public final class MemberDiscountDocumentTestFixture {

    private MemberDiscountDocumentTestFixture() {
    }

    public static void apply(CommercialDocument document, BigDecimal percentage) {
        var eligibleProductIds = document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                .map(DocumentLine::getProductoId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var previousLineCount = document.getLineas().size();
        var eligibleBase = DocumentPercentDiscountAllocator.apply(
                document, percentage, Set.copyOf(eligibleProductIds));
        var discountLines = document.getLineas().stream()
                .skip(previousLineCount)
                .filter(line -> line.getLineType() == DocumentLineType.DOCUMENT_DISCOUNT)
                .toList();
        var amount = Money.euros(discountLines.stream()
                .map(DocumentLine::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .negate());
        var adjustment = new DocumentAdjustment(
                document, "MEMBER_PERCENT", 1, percentage, eligibleBase, amount,
                UUID.randomUUID(), Instant.parse("2026-09-18T10:00:00Z"),
                UUID.randomUUID(), UUID.randomUUID(), "Miembro");
        document.addAdjustment(adjustment);
        discountLines.forEach(line -> line.linkDocumentAdjustment(
                adjustment.getId(), line.getSourceLineId()));
    }
}

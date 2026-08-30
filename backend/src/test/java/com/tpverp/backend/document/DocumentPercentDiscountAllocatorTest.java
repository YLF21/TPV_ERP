package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentPercentDiscountAllocatorTest {

    @Test
    void percentageUsesTheNetAfterCoupon() {
        var document = document();
        var product = addProduct(document, 1, "100.00", "IVA", "21.00");
        document.addLine(DocumentLine.special(
                document, 2, "CUPON", new BigDecimal("-50.00"), true,
                "IVA", new BigDecimal("21.00"), null, null, UUID.randomUUID()));

        DocumentPercentDiscountAllocator.apply(
                document, new BigDecimal("10.00"), Set.of(product.getProductoId()));

        var discount = documentDiscount(document);
        assertThat(discount.getTotal()).isEqualByComparingTo("-5.00");
        assertThat(discount.getSourceLineId()).isEqualTo(product.getId());
        assertBalanced(document);
    }

    @Test
    void exhaustedPromotionTaxGroupReceivesNoFurtherDiscount() {
        var document = document();
        var exhausted = addProduct(document, 1, "20.00", "IVA", "21.00");
        var remaining = addProduct(document, 2, "10.00", "IVA", "10.00");
        var promotion = DocumentLine.special(
                document, 3, "PROMOCION", new BigDecimal("-20.00"), true,
                "IVA", new BigDecimal("21.00"), UUID.randomUUID(), null, null);
        promotion.assignPromotionAffectedPositions(Set.of(1));
        document.addLine(promotion);

        DocumentPercentDiscountAllocator.apply(
                document,
                new BigDecimal("10.00"),
                Set.of(exhausted.getProductoId(), remaining.getProductoId()));

        var discounts = document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.DOCUMENT_DISCOUNT)
                .toList();
        assertThat(discounts).singleElement().satisfies(discount -> {
            assertThat(discount.getTotal()).isEqualByComparingTo("-1.00");
            assertThat(discount.getPorcentajeImpuesto()).isEqualByComparingTo("10.00");
            assertThat(discount.getSourceLineId()).isEqualTo(remaining.getId());
        });
        assertBalanced(document);
    }

    @Test
    void protectedProductsDoNotContributeToPercentageWeight() {
        var document = document();
        var eligible = addProduct(document, 1, "10.00", "IVA", "21.00");
        var protectedProduct = addProduct(document, 2, "100.00", "IVA", "21.00");

        DocumentPercentDiscountAllocator.apply(
                document, new BigDecimal("10.00"), Set.of(eligible.getProductoId()));

        var discount = documentDiscount(document);
        assertThat(discount.getTotal()).isEqualByComparingTo("-1.00");
        assertThat(discount.getSourceLineId()).isEqualTo(eligible.getId());
        assertThat(protectedProduct.getTotal()).isEqualByComparingTo("100.00");
        assertBalanced(document);
    }

    @Test
    void couponUsesEligibleNetAfterPromotionAndRejectsAnExcess() {
        var document = document();
        var eligible = addProduct(document, 1, "10.00", "IVA", "10.00");
        addProduct(document, 2, "100.00", "IVA", "21.00");
        var promotion = DocumentLine.special(
                document, 3, "PROMOCION", new BigDecimal("-9.00"), true,
                "IVA", new BigDecimal("10.00"), UUID.randomUUID(), null, null);
        promotion.assignPromotionAffectedPositions(Set.of(1));
        document.addLine(promotion);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                DocumentService.addPromotionalCouponLines(
                        document, UUID.randomUUID(), UUID.randomUUID(), "1234",
                        new BigDecimal("5.00"), Set.of(eligible.getProductoId())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("coupon_discount_exceeds_eligible_total");

        DocumentService.addPromotionalCouponLines(
                document, UUID.randomUUID(), UUID.randomUUID(), "1234",
                new BigDecimal("1.00"), Set.of(eligible.getProductoId()));

        var coupon = document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PROMOTIONAL_COUPON)
                .findFirst().orElseThrow();
        assertThat(coupon.getTotal()).isEqualByComparingTo("-1.00");
        assertThat(coupon.getPorcentajeImpuesto()).isEqualByComparingTo("10.00");
        assertBalanced(document);
    }

    @Test
    void couponCannotBeAppliedToAnAllProtectedDocument() {
        var document = document();
        addProduct(document, 1, "100.00", "IVA", "21.00");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                DocumentService.addPromotionalCouponLines(
                        document, UUID.randomUUID(), UUID.randomUUID(), "1234",
                        BigDecimal.ONE, Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("coupon_document_without_eligible_lines");
        assertThat(document.getLineas())
                .noneMatch(line -> line.getLineType() == DocumentLineType.PROMOTIONAL_COUPON);
    }

    @Test
    void returnsTheExactEligibleBaseInsteadOfInferringItFromRoundedDiscount() {
        var document = document();
        var product = addProduct(document, 1, "0.05", "IVA", "0.00");

        var eligibleBase = DocumentPercentDiscountAllocator.apply(
                document, new BigDecimal("10.00"), Set.of(product.getProductoId()));

        assertThat(eligibleBase).isEqualByComparingTo("0.05");
        assertThat(documentDiscount(document).getTotal()).isEqualByComparingTo("-0.01");
    }

    private static CommercialDocument document() {
        return new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 27), UUID.randomUUID(), BigDecimal.ZERO);
    }

    private static DocumentLine addProduct(
            CommercialDocument document,
            int position,
            String price,
            String regime,
            String percentage) {
        var line = new DocumentLine(
                document, UUID.randomUUID(), position, BigDecimal.ONE,
                "P-" + position, "Producto " + position, "VENTA",
                new BigDecimal(price), BigDecimal.ZERO, true,
                regime, new BigDecimal(percentage));
        document.addLine(line);
        return line;
    }

    private static DocumentLine documentDiscount(CommercialDocument document) {
        return document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.DOCUMENT_DISCOUNT)
                .findFirst()
                .orElseThrow();
    }

    private static void assertBalanced(CommercialDocument document) {
        assertThat(document.getBaseTotal().add(document.getImpuestoTotal()))
                .isEqualByComparingTo(document.getTotal());
    }
}

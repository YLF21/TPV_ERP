package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CheckoutDiscountAllocatorTest {

    @Test
    void reducesFortyEurosAtTwentyOnePercentToTheRequiredFiscalTotals() {
        var document = document();
        addProduct(document, 1, "40.00", true, "IVA", "21.00");

        CheckoutDiscountAllocator.apply(document, new BigDecimal("5.00"));

        assertThat(document.getTotal()).isEqualByComparingTo("35.00");
        assertThat(document.getBaseTotal()).isEqualByComparingTo("28.93");
        assertThat(document.getImpuestoTotal()).isEqualByComparingTo("6.07");
        assertBalanced(document);
        assertThat(manualDiscount(document).getTotal()).isEqualByComparingTo("-5.00");
    }

    @Test
    void recalculatesTheReportedTicketFromSixteenFortyToElevenForty() {
        var document = document();
        addProduct(document, 1, "16.40", true, "IVA", "21.00");

        CheckoutDiscountAllocator.apply(document, new BigDecimal("5.00"));

        assertThat(document.getTotal()).isEqualByComparingTo("11.40");
        assertThat(document.getBaseTotal()).isEqualByComparingTo("9.42");
        assertThat(document.getImpuestoTotal()).isEqualByComparingTo("1.98");
        assertBalanced(document);
        assertThat(manualDiscount(document).getBase()).isEqualByComparingTo("-4.13");
        assertThat(manualDiscount(document).getImpuesto()).isEqualByComparingTo("-0.87");
        assertThat(manualDiscount(document).getTotal()).isEqualByComparingTo("-5.00");
    }

    @Test
    void allocatesAfterPromotionsAndCouponsUsingFinalTotalsPerTaxRate() {
        var document = document();
        addProduct(document, 1, "20.00", true, "IVA", "21.00");
        addProduct(document, 2, "20.00", true, "IVA", "10.00");
        addProduct(document, 3, "20.00", true, "IVA", "4.00");
        document.addLine(DocumentLine.special(
                document, 4, "PROMOCION", new BigDecimal("-10.00"), true,
                "IVA", new BigDecimal("21.00"), UUID.randomUUID(), null, null));
        document.addLine(DocumentLine.special(
                document, 5, "CUPON", new BigDecimal("-5.00"), true,
                "IVA", new BigDecimal("10.00"), UUID.randomUUID(), null,
                UUID.randomUUID()));

        CheckoutDiscountAllocator.apply(document, new BigDecimal("5.00"));

        assertThat(manualDiscountAt(document, "4.00").getTotal())
                .isEqualByComparingTo("-2.22");
        assertThat(manualDiscountAt(document, "10.00").getTotal())
                .isEqualByComparingTo("-1.67");
        assertThat(manualDiscountAt(document, "21.00").getTotal())
                .isEqualByComparingTo("-1.11");
        assertThat(document.getTotal()).isEqualByComparingTo("40.00");
        assertThat(document.getBaseTotal()).isEqualByComparingTo("36.56");
        assertThat(document.getImpuestoTotal()).isEqualByComparingTo("3.44");
        assertBalanced(document);
    }

    @Test
    void appliesF11AfterLineDiscountPromotionAndCoupon() {
        var document = document();
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, BigDecimal.ONE,
                "P-DISCOUNTS", "Producto con descuentos", "VENTA",
                new BigDecimal("50.00"), new BigDecimal("10.00"),
                true, "IVA", new BigDecimal("21.00")));
        document.addLine(DocumentLine.special(
                document, 2, "PROMOCION", new BigDecimal("-3.00"), true,
                "IVA", new BigDecimal("21.00"), UUID.randomUUID(), null, null));
        document.addLine(DocumentLine.special(
                document, 3, "CUPON", new BigDecimal("-2.00"), true,
                "IVA", new BigDecimal("21.00"), UUID.randomUUID(), null,
                UUID.randomUUID()));

        CheckoutDiscountAllocator.apply(document, new BigDecimal("5.00"));

        assertThat(document.getTotal()).isEqualByComparingTo("35.00");
        assertThat(document.getBaseTotal()).isEqualByComparingTo("28.93");
        assertThat(document.getImpuestoTotal()).isEqualByComparingTo("6.07");
        assertBalanced(document);
    }

    @Test
    void supportsExemptAndIgicRegimes() {
        var document = document();
        addProduct(document, 1, "10.00", true, "IVA", "0.00");
        addProduct(document, 2, "10.00", true, "IGIC", "7.00");

        CheckoutDiscountAllocator.apply(document, new BigDecimal("2.00"));

        assertThat(manualDiscountAt(document, "IVA", "0.00").getTotal())
                .isEqualByComparingTo("-1.00");
        assertThat(manualDiscountAt(document, "IGIC", "7.00").getTotal())
                .isEqualByComparingTo("-1.00");
        assertThat(document.getTotal()).isEqualByComparingTo("18.00");
        assertBalanced(document);
    }

    @Test
    void reducesTheCustomerTotalExactlyWhenCataloguePricesExcludeTax() {
        var document = document();
        addProduct(document, 1, "100.00", false, "IVA", "21.00");

        CheckoutDiscountAllocator.apply(document, new BigDecimal("5.00"));

        assertThat(document.getTotal()).isEqualByComparingTo("116.00");
        assertThat(document.getBaseTotal()).isEqualByComparingTo("95.87");
        assertThat(document.getImpuestoTotal()).isEqualByComparingTo("20.13");
        assertThat(manualDiscount(document).isImpuestosIncluidos()).isTrue();
        assertBalanced(document);
    }

    @Test
    void assignsResidualCentsByStableTaxOrder() {
        var document = document();
        addProduct(document, 1, "1.00", true, "IVA", "21.00");
        addProduct(document, 2, "1.00", true, "IVA", "10.00");
        addProduct(document, 3, "1.00", true, "IVA", "4.00");

        CheckoutDiscountAllocator.apply(document, new BigDecimal("0.02"));

        assertThat(manualDiscountAt(document, "4.00").getTotal())
                .isEqualByComparingTo("-0.01");
        assertThat(manualDiscountAt(document, "10.00").getTotal())
                .isEqualByComparingTo("-0.01");
        assertThat(document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.MANUAL_DISCOUNT)
                .filter(line -> line.getPorcentajeImpuesto().compareTo(new BigDecimal("21")) == 0))
                .isEmpty();
        assertThat(document.getTotal()).isEqualByComparingTo("2.98");
        assertBalanced(document);
    }

    @Test
    void discountsOnlyThePositiveSalePartWhenTheTicketAlsoContainsAReturn() {
        var document = document();
        addProduct(document, 1, "20.00", true, "IVA", "21.00");
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 2, BigDecimal.ONE.negate(), "RETURN",
                "Devolucion", "VENTA", new BigDecimal("10.00"), BigDecimal.ZERO,
                true, "IVA", new BigDecimal("10.00")));

        CheckoutDiscountAllocator.apply(document, new BigDecimal("5.00"));

        assertThat(manualDiscount(document).getPorcentajeImpuesto())
                .isEqualByComparingTo("21.00");
        assertThat(document.getTotal()).isEqualByComparingTo("5.00");
        assertBalanced(document);
    }

    @Test
    void mixedEligibleLinesAllocateOnlyToEligibleTaxGroups() {
        var document = document();
        addProduct(document, 1, "10.00", true, "IVA", "21.00");
        addProduct(document, 2, "10.00", true, "IGIC", "7.00");
        var protectedLine = addProduct(document, 3, "30.00", true, "IVA", "21.00");
        var eligible = document.getLineas().stream().filter(line -> line.getPosicion() < 3)
                .map(DocumentLine::getProductoId).collect(java.util.stream.Collectors.toSet());

        CheckoutDiscountAllocator.apply(document, new BigDecimal("5.00"), eligible);

        assertThat(document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.MANUAL_DISCOUNT)
                .map(DocumentLine::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("-5.00");
        assertThat(protectedLine.getTotal()).isEqualByComparingTo("30.00");
    }

    @Test
    void rejectsFixedDiscountAboveEligibleSubtotalAndAllProtectedCart() {
        var document = document();
        var line = addProduct(document, 1, "2.00", true, "IVA", "21.00");
        addProduct(document, 2, "10.00", true, "IVA", "21.00");
        var eligible = Set.of(line.getProductoId());
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                CheckoutDiscountAllocator.apply(document, new BigDecimal("2.01"), eligible))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("checkout_discount_exceeds_eligible_total");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                CheckoutDiscountAllocator.apply(document, new BigDecimal("1.00"), Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("checkout_discount_without_eligible_lines");
    }

    @Test
    void memberBalanceCanReduceTheEligibleTicketToZeroAndKeepsTaxesBalanced() {
        var document = document();
        var productId = UUID.randomUUID();
        document.addLine(new DocumentLine(
                document, productId, 1, BigDecimal.ONE, "P-1", "Producto", "VENTA",
                new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21.00")));

        CheckoutDiscountAllocator.applyMemberBalance(
                document, new BigDecimal("10.00"), java.util.Set.of(productId));

        assertThat(document.getTotal()).isZero();
        assertThat(document.getBaseTotal()).isZero();
        assertThat(document.getImpuestoTotal()).isZero();
        assertThat(memberBalance(document).getTotal()).isEqualByComparingTo("-10.00");
        assertBalanced(document);
    }

    @Test
    void memberBalanceUsesMixedTaxWeightsAndLeavesBlockedProductsUntouched() {
        var document = document();
        var standardTaxProductId = UUID.randomUUID();
        var reducedTaxProductId = UUID.randomUUID();
        var blockedProductId = UUID.randomUUID();
        document.addLine(new DocumentLine(
                document, standardTaxProductId, 1, BigDecimal.ONE,
                "P-1", "Producto IVA general", "VENTA",
                new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21.00")));
        document.addLine(new DocumentLine(
                document, reducedTaxProductId, 2, BigDecimal.ONE,
                "P-2", "Producto IVA reducido", "VENTA",
                new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("10.00")));
        document.addLine(new DocumentLine(
                document, blockedProductId, 3, BigDecimal.ONE,
                "P-3", "Producto excluido", "VENTA",
                new BigDecimal("20.00"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("4.00")));

        CheckoutDiscountAllocator.applyMemberBalance(
                document,
                new BigDecimal("4.00"),
                java.util.Set.of(standardTaxProductId, reducedTaxProductId));

        assertThat(memberBalanceAt(document, "21.00").getTotal())
                .isEqualByComparingTo("-2.00");
        assertThat(memberBalanceAt(document, "10.00").getTotal())
                .isEqualByComparingTo("-2.00");
        assertThat(document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.MEMBER_BALANCE)
                .filter(line -> line.getPorcentajeImpuesto()
                        .compareTo(new BigDecimal("4.00")) == 0))
                .isEmpty();
        assertThat(document.getTotal()).isEqualByComparingTo("36.00");
        assertBalanced(document);
    }

    private static CommercialDocument document() {
        return new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 17), UUID.randomUUID(), BigDecimal.ZERO);
    }

    private static DocumentLine addProduct(
            CommercialDocument document,
            int position,
            String price,
            boolean taxesIncluded,
            String regime,
            String taxPercentage) {
        var line = new DocumentLine(
                document, UUID.randomUUID(), position, BigDecimal.ONE,
                "P-" + position, "Producto " + position, "VENTA",
                new BigDecimal(price), BigDecimal.ZERO, taxesIncluded,
                regime, new BigDecimal(taxPercentage));
        document.addLine(line);
        return line;
    }

    private static DocumentLine manualDiscount(CommercialDocument document) {
        return document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.MANUAL_DISCOUNT)
                .findFirst().orElseThrow();
    }

    private static DocumentLine memberBalance(CommercialDocument document) {
        return document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.MEMBER_BALANCE)
                .findFirst().orElseThrow();
    }

    private static DocumentLine memberBalanceAt(
            CommercialDocument document,
            String taxPercentage) {
        return document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.MEMBER_BALANCE)
                .filter(line -> line.getPorcentajeImpuesto()
                        .compareTo(new BigDecimal(taxPercentage)) == 0)
                .findFirst().orElseThrow();
    }

    private static DocumentLine manualDiscountAt(
            CommercialDocument document,
            String taxPercentage) {
        return manualDiscountAt(document, "IVA", taxPercentage);
    }

    private static DocumentLine manualDiscountAt(
            CommercialDocument document,
            String regime,
            String taxPercentage) {
        return document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.MANUAL_DISCOUNT)
                .filter(line -> line.getRegimenImpuesto().equals(regime))
                .filter(line -> line.getPorcentajeImpuesto()
                        .compareTo(new BigDecimal(taxPercentage)) == 0)
                .findFirst().orElseThrow();
    }

    private static void assertBalanced(CommercialDocument document) {
        assertThat(document.getBaseTotal().add(document.getImpuestoTotal()))
                .isEqualByComparingTo(document.getTotal());
    }
}

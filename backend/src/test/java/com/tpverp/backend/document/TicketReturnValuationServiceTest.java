package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.QuantityPricing;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.party.MemberDocumentLoyaltyLineRepository;
import com.tpverp.backend.party.MemberDocumentLoyaltyLine;
import com.tpverp.backend.party.MemberDocumentLoyaltySettlementRepository;
import com.tpverp.backend.promotion.Promotion;
import com.tpverp.backend.promotion.PromotionEngine;
import com.tpverp.backend.promotion.PromotionRepository;
import com.tpverp.backend.promotion.PromotionTarget;
import com.tpverp.backend.promotion.PromotionTargetRepository;
import com.tpverp.backend.promotion.PromotionTargetType;
import com.tpverp.backend.promotion.PromotionType;
import com.tpverp.backend.promotion.PromotionalCoupon;
import com.tpverp.backend.promotion.PromotionalCouponRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TicketReturnValuationServiceTest {

    private final CommercialDocumentRepository documents =
            mock(CommercialDocumentRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final MemberDocumentLoyaltyLineRepository loyaltyLines =
            mock(MemberDocumentLoyaltyLineRepository.class);
    private final MemberDocumentLoyaltySettlementRepository loyaltySettlements =
            mock(MemberDocumentLoyaltySettlementRepository.class);
    private final PromotionRepository promotions = mock(PromotionRepository.class);
    private final PromotionTargetRepository targets =
            mock(PromotionTargetRepository.class);
    private final PromotionalCouponRepository coupons =
            mock(PromotionalCouponRepository.class);
    private final PromotionEngine engine = org.mockito.Mockito.spy(new PromotionEngine());
    private final UUID storeId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final Product product = mock(Product.class);

    @BeforeEach
    void setUp() {
        when(product.getId()).thenReturn(productId);
        when(product.getDiscountType()).thenReturn(DiscountType.NORMAL);
        when(products.findAllByStoreIdAndIdIn(any(), any()))
                .thenReturn(List.of(product));
        when(loyaltyLines.findAllById(any())).thenReturn(List.of());
        when(documents.confirmedRefundedQuantity(any()))
                .thenReturn(new BigDecimal("0.000"));
        when(documents.confirmedReturnAmount(any()))
                .thenReturn(new BigDecimal("0.00"));
        when(coupons.findAllById(any())).thenReturn(List.of());
    }

    @Test
    void threeForTwoReturnsZeroWhenOnlyOneUnitIsReturned() {
        var promotion = buyXPayY(3, 2);
        var fixture = ticketWithPromotion(3, "10.00", "10.00", promotion);
        historicalPromotion(promotion);

        var result = service().value(
                fixture.ticket(), Map.of(fixture.productLine().getId(), BigDecimal.ONE));

        assertThat(result.selectedGross()).isEqualByComparingTo("10.00");
        assertThat(result.lostBenefits()).isEqualByComparingTo("10.00");
        assertThat(result.refundableAmount()).isEqualByComparingTo("0.00");
        assertThat(result.remainingBasketValue()).isEqualByComparingTo("20.00");
        assertThat(result.taxAdjustments()).singleElement().satisfies(adjustment -> {
            assertThat(adjustment.taxRegime()).isEqualTo("IVA");
            assertThat(adjustment.taxPercentage()).isEqualByComparingTo("21.00");
            assertThat(adjustment.amount()).isEqualByComparingTo("10.00");
        });
    }

    @Test
    void threeForTwoReturnsOneUnitWhenTwoUnitsAreReturnedTogether() {
        var promotion = buyXPayY(3, 2);
        var fixture = ticketWithPromotion(3, "10.00", "10.00", promotion);
        historicalPromotion(promotion);

        var result = service().value(
                fixture.ticket(),
                Map.of(fixture.productLine().getId(), new BigDecimal("2.000")));

        assertThat(result.selectedGross()).isEqualByComparingTo("20.00");
        assertThat(result.lostBenefits()).isEqualByComparingTo("10.00");
        assertThat(result.refundableAmount()).isEqualByComparingTo("10.00");
        assertThat(result.remainingBasketValue()).isEqualByComparingTo("10.00");
        assertThat(result.taxAdjustments()).singleElement()
                .extracting(TicketReturnValuationService.TaxAdjustment::amount)
                .isEqualTo(new BigDecimal("10.00"));
    }

    @Test
    void threeForTwoNeverRefundsMoreThanPaidWhenAllUnitsAreReturnedTogether() {
        var promotion = buyXPayY(3, 2);
        var fixture = ticketWithPromotion(3, "10.00", "10.00", promotion);
        historicalPromotion(promotion);

        var result = service().value(
                fixture.ticket(),
                Map.of(fixture.productLine().getId(), new BigDecimal("3.000")));

        assertThat(result.selectedGross()).isEqualByComparingTo("30.00");
        assertThat(result.lostBenefits()).isEqualByComparingTo("10.00");
        assertThat(result.refundableAmount()).isEqualByComparingTo("20.00");
        assertThat(result.remainingBasketValue()).isEqualByComparingTo("0.00");
    }

    @Test
    void secondUnitAtHalfPriceReturnsFiveWhenOneUnitIsReturned() {
        var promotion = secondUnitPercent("50.00");
        var fixture = ticketWithPromotion(2, "10.00", "5.00", promotion);
        historicalPromotion(promotion);

        var result = service().value(
                fixture.ticket(), Map.of(fixture.productLine().getId(), BigDecimal.ONE));

        assertThat(result.selectedGross()).isEqualByComparingTo("10.00");
        assertThat(result.lostBenefits()).isEqualByComparingTo("5.00");
        assertThat(result.refundableAmount()).isEqualByComparingTo("5.00");
        assertThat(result.remainingBasketValue()).isEqualByComparingTo("10.00");
        assertThat(result.taxAdjustments()).singleElement()
                .extracting(TicketReturnValuationService.TaxAdjustment::amount)
                .isEqualTo(new BigDecimal("5.00"));
    }

    @Test
    void nonDiscountableProductRefundDoesNotReverseLoyaltyAccrual() {
        when(product.getDiscountType()).thenReturn(DiscountType.NONE);
        var fixture = ticketWithoutPromotion(1, "10.00");

        var result = service().value(
                fixture.ticket(), Map.of(fixture.productLine().getId(), BigDecimal.ONE));

        assertThat(result.refundableAmount()).isEqualByComparingTo("10.00");
        assertThat(result.eligibleRefundableAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void historicalEligibilityWinsOverLaterCatalogChanges() {
        var fixture = ticketWithoutPromotion(1, "10.00");
        when(product.getDiscountType()).thenReturn(DiscountType.NONE);
        when(loyaltyLines.findAllById(any())).thenReturn(List.of(
                new MemberDocumentLoyaltyLine(
                        fixture.ticket().getId(),
                        fixture.productLine().getId(),
                        true,
                        new BigDecimal("10.00"))));

        var result = service().value(
                fixture.ticket(), Map.of(fixture.productLine().getId(), BigDecimal.ONE));

        assertThat(result.eligibleRefundableAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    void repeatedReturnsUseConfirmedQuantityAndPreviouslyRefundedAmount() {
        var promotion = buyXPayY(3, 2);
        var fixture = ticketWithPromotion(3, "10.00", "10.00", promotion);
        historicalPromotion(promotion);
        when(documents.confirmedRefundedQuantity(fixture.productLine().getId()))
                .thenReturn(new BigDecimal("1.000"));
        when(documents.confirmedReturnAmount(fixture.ticket().getId()))
                .thenReturn(new BigDecimal("0.00"));

        var secondReturn = service().value(
                fixture.ticket(), Map.of(fixture.productLine().getId(), BigDecimal.ONE));

        assertThat(secondReturn.refundableAmount()).isEqualByComparingTo("10.00");
        assertThat(secondReturn.cumulativeRefundableAmount())
                .isEqualByComparingTo("10.00");
        assertThat(secondReturn.remainingBasketValue()).isEqualByComparingTo("10.00");

        when(documents.confirmedRefundedQuantity(fixture.productLine().getId()))
                .thenReturn(new BigDecimal("2.000"));
        when(documents.confirmedReturnAmount(fixture.ticket().getId()))
                .thenReturn(new BigDecimal("10.00"));

        var finalReturn = service().value(
                fixture.ticket(), Map.of(fixture.productLine().getId(), BigDecimal.ONE));

        assertThat(finalReturn.refundableAmount()).isEqualByComparingTo("10.00");
        assertThat(finalReturn.cumulativeRefundableAmount())
                .isEqualByComparingTo("20.00");
        assertThat(finalReturn.remainingBasketValue()).isEqualByComparingTo("0.00");
    }

    @Test
    void historicalAmountCouponIsRetainedWhenValuingPartialAndTotalReturns() {
        var fixture = ticketWithoutPromotion(2, "10.00");
        var promotionId = UUID.randomUUID();
        var coupon = PromotionalCoupon.amount(
                UUID.randomUUID(), storeId, promotionId, UUID.randomUUID(),
                "hash", "1234", new BigDecimal("5.00"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        fixture.ticket().addLine(DocumentLine.special(
                fixture.ticket(), 2, "CUPON HISTORICO", new BigDecimal("-5.00"),
                true, "IVA", new BigDecimal("21.00"), promotionId, null,
                coupon.id()));
        when(coupons.findAllById(any())).thenReturn(List.of(coupon));

        var partial = service().value(
                fixture.ticket(), Map.of(fixture.productLine().getId(), BigDecimal.ONE));
        var total = service().value(
                fixture.ticket(),
                Map.of(fixture.productLine().getId(), new BigDecimal("2.000")));

        assertThat(partial.refundableAmount()).isEqualByComparingTo("10.00");
        assertThat(partial.remainingBasketValue()).isEqualByComparingTo("5.00");
        assertThat(total.refundableAmount()).isEqualByComparingTo("15.00");
        assertThat(total.remainingBasketValue()).isEqualByComparingTo("0.00");
    }

    @Test
    void partialReturnKeepsTheProportionalPersistedF11Discount() {
        var fixture = ticketWithoutPromotion(2, "20.00");
        CheckoutDiscountAllocator.apply(fixture.ticket(), new BigDecimal("5.00"));

        var partial = service().value(
                fixture.ticket(), Map.of(fixture.productLine().getId(), BigDecimal.ONE));
        var total = service().value(
                fixture.ticket(),
                Map.of(fixture.productLine().getId(), new BigDecimal("2.000")));

        assertThat(partial.refundableAmount()).isEqualByComparingTo("17.50");
        assertThat(partial.remainingBasketValue()).isEqualByComparingTo("17.50");
        assertThat(total.refundableAmount()).isEqualByComparingTo("35.00");
        assertThat(total.remainingBasketValue()).isEqualByComparingTo("0.00");
    }

    @Test
    void mixedTaxPartialReturnKeepsPromotionNetDiscountAsFinalSavingWithoutDuplicatingTax() {
        var promotion = buyXPayY(3, 2);
        var ticket = new CommercialDocument(storeId, UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 2), UUID.randomUUID(), BigDecimal.ZERO);
        var netLine = new DocumentLine(ticket, productId, 1, 3, "NET", "Neto", "GENERAL",
                new BigDecimal("10.00"), BigDecimal.ZERO, false, "IVA", new BigDecimal("21"));
        var secondId = UUID.randomUUID();
        var grossLine = new DocumentLine(ticket, secondId, 2, 1, "GROSS", "Final", "GENERAL",
                new BigDecimal("20.00"), BigDecimal.ZERO, true, "IVA", new BigDecimal("10"));
        ticket.addLine(netLine);
        ticket.addLine(grossLine);
        ticket.addLine(DocumentLine.special(ticket, 3, "PROMOCION 3x2", new BigDecimal("-10.00"),
                false, "IVA", new BigDecimal("21"), promotion.rootVersionId(), promotion.id(), null));
        ticket.getLineas().get(2).assignPromotionEngineVersion(PromotionEngine.CURRENT_VERSION);
        var secondProduct = mock(Product.class);
        when(secondProduct.getId()).thenReturn(secondId);
        when(secondProduct.getDiscountType()).thenReturn(DiscountType.NORMAL);
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product, secondProduct));
        when(promotions.findAllById(any())).thenReturn(List.of(promotion));
        when(targets.findByPromocionIdIn(any())).thenReturn(List.of());

        var partial = service().value(ticket, Map.of(netLine.getId(), BigDecimal.ONE));
        var total = service().value(ticket, Map.of(netLine.getId(), new BigDecimal("3"), grossLine.getId(), BigDecimal.ONE));

        assertThat(ticket.getTotal()).isEqualByComparingTo("44.20");
        assertThat(partial.selectedGross()).isEqualByComparingTo("12.10");
        assertThat(partial.refundableAmount()).isEqualByComparingTo("12.10");
        assertThat(partial.remainingBasketValue()).isEqualByComparingTo("32.10");
        assertThat(partial.lostBenefits()).isZero();
        assertThat(partial.taxAdjustments()).isEmpty();
        assertThat(total.refundableAmount()).isEqualByComparingTo("44.20");
        assertThat(total.remainingBasketValue()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void returningUnrelatedProductKeepsOriginalPromotionEngineRules(int engineVersion) {
        var promotion = buyXPayY(3, 2);
        var ticket = new CommercialDocument(storeId, UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 2), UUID.randomUUID(), BigDecimal.ZERO);
        var secondId = UUID.randomUUID();
        var unrelatedId = UUID.randomUUID();
        var first = new DocumentLine(ticket, productId, 1, 4, "A", "A", "GENERAL",
                new BigDecimal("10"), BigDecimal.ZERO, true, "IVA", new BigDecimal("21"));
        var second = new DocumentLine(ticket, secondId, 2, 2, "B", "B", "GENERAL",
                new BigDecimal("9"), BigDecimal.ZERO, true, "IVA", new BigDecimal("10"));
        var unrelated = new DocumentLine(ticket, unrelatedId, 3, 1, "C", "C", "GENERAL",
                BigDecimal.ONE, BigDecimal.ZERO, true, "IVA", new BigDecimal("21"));
        ticket.addLine(first);
        ticket.addLine(second);
        ticket.addLine(unrelated);
        var oldDiscount = DocumentLine.special(ticket, 4, "PROMOCION 3x2", new BigDecimal("-10"),
                true, "IVA", new BigDecimal("21"), promotion.rootVersionId(), promotion.id(), null);
        ticket.addLine(oldDiscount);
        if (engineVersion == 2) {
            oldDiscount.assignPromotionEngineVersion(2);
            var extraDiscount = DocumentLine.special(ticket, 5, "PROMOCION 3x2", new BigDecimal("-9"),
                    true, "IVA", new BigDecimal("10"), promotion.rootVersionId(), promotion.id(), null);
            extraDiscount.assignPromotionEngineVersion(2);
            ticket.addLine(extraDiscount);
        }
        var secondProduct = mock(Product.class);
        when(secondProduct.getId()).thenReturn(secondId);
        when(secondProduct.getDiscountType()).thenReturn(DiscountType.NORMAL);
        var unrelatedProduct = mock(Product.class);
        when(unrelatedProduct.getId()).thenReturn(unrelatedId);
        when(unrelatedProduct.getDiscountType()).thenReturn(DiscountType.NORMAL);
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenAnswer(invocation -> {
            java.util.Collection<UUID> requested = invocation.getArgument(1);
            return List.of(product, secondProduct, unrelatedProduct).stream()
                    .filter(item -> requested.contains(item.getId())).toList();
        });
        when(promotions.findAllById(any())).thenReturn(List.of(promotion));
        when(targets.findByPromocionIdIn(any())).thenReturn(List.of(
                new PromotionTarget(promotion.id(), PromotionTargetType.PRODUCT, productId),
                new PromotionTarget(promotion.id(), PromotionTargetType.PRODUCT, secondId)));

        var result = service().value(ticket, Map.of(unrelated.getId(), BigDecimal.ONE));

        assertThat(ticket.getTotal()).isEqualByComparingTo(engineVersion == 1 ? "49" : "40");
        assertThat(result.refundableAmount()).isEqualByComparingTo("1");
        assertThat(result.remainingBasketValue()).isEqualByComparingTo(engineVersion == 1 ? "48" : "39");
        assertThat(result.lostBenefits()).isZero();
        assertThat(result.taxAdjustments()).isEmpty();
    }

    @Test
    void newQuantitySnapshotWithoutWinningAdjustmentRetainsVersionThroughPartialReturn() {
        var fixture = ticketWithoutPromotion(3, "2.00");
        fixture.productLine().assignQuantityPriceSnapshot(new QuantityPriceSnapshot(
                new QuantityPricing(QuantityPricing.Mode.UNIT_THRESHOLD,
                        List.of(new QuantityPricing.Tier(4, new BigDecimal("1.50")))), new BigDecimal("2.00")));
        fixture.productLine().assignPromotionEngineVersion(3);

        var result = service().value(fixture.ticket(), Map.of(fixture.productLine().getId(), BigDecimal.ONE));

        assertThat(result.refundableAmount()).isEqualByComparingTo("2.00");
        assertThat(result.remainingBasketValue()).isEqualByComparingTo("4.00");
        org.mockito.Mockito.verify(engine).preview(any());
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.never()).previewVersion2(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void largeBasketReturnReplaysTheOriginalQuantityPricingEngine(int engineVersion) {
        var fixture = ticketWithoutPromotion(100, "12.10");
        var ticket = fixture.ticket();
        var first = fixture.productLine();
        first.assignQuantityPriceSnapshot(new QuantityPriceSnapshot(
                new QuantityPricing(QuantityPricing.Mode.PACK,
                        List.of(new QuantityPricing.Tier(100, new BigDecimal("605.00")))), new BigDecimal("12.10")));
        if (engineVersion == 3) first.assignPromotionEngineVersion(3);
        var secondId = UUID.randomUUID();
        var unrelatedId = UUID.randomUUID();
        var second = new DocumentLine(ticket, secondId, 2, 51, "B", "B", "GENERAL",
                new BigDecimal("10.89"), BigDecimal.ZERO, true, "IVA", new BigDecimal("21"));
        var unrelated = new DocumentLine(ticket, unrelatedId, 3, 1, "C", "C", "GENERAL",
                new BigDecimal("1.21"), BigDecimal.ZERO, true, "IVA", new BigDecimal("21"));
        ticket.addLine(second);
        ticket.addLine(unrelated);
        var quantityDiscount = DocumentLine.quantityPrice(ticket, 4, "100 x 605", new BigDecimal("-605.00"),
                true, "IVA", new BigDecimal("21"));
        quantityDiscount.assignPromotionEngineVersion(engineVersion);
        ticket.addLine(quantityDiscount);
        var promotion = buyXPayY(3, 2);
        int packs = engineVersion == 2 ? 16 : 17;
        // Historical v2 greedily consumed two B units together with the reserved A;
        // v3 regroups all B units after selecting A's quantity price.
        for (int index = 0; index < packs; index++) {
            var discount = DocumentLine.special(ticket, 5 + index, "PROMOCION 3x2", new BigDecimal("-10.89"),
                    true, "IVA", new BigDecimal("21"), promotion.rootVersionId(), promotion.id(), null);
            discount.assignPromotionEngineVersion(engineVersion);
            ticket.addLine(discount);
        }
        var secondProduct = mock(Product.class);
        when(secondProduct.getId()).thenReturn(secondId);
        when(secondProduct.getDiscountType()).thenReturn(DiscountType.NORMAL);
        var unrelatedProduct = mock(Product.class);
        when(unrelatedProduct.getId()).thenReturn(unrelatedId);
        when(unrelatedProduct.getDiscountType()).thenReturn(DiscountType.NORMAL);
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenAnswer(invocation -> {
            java.util.Collection<UUID> requested = invocation.getArgument(1);
            return List.of(product, secondProduct, unrelatedProduct).stream()
                    .filter(item -> requested.contains(item.getId())).toList();
        });
        when(promotions.findAllById(any())).thenReturn(List.of(promotion));
        when(targets.findByPromocionIdIn(any())).thenReturn(List.of(
                new PromotionTarget(promotion.id(), PromotionTargetType.PRODUCT, productId),
                new PromotionTarget(promotion.id(), PromotionTargetType.PRODUCT, secondId)));

        var result = service().value(ticket, Map.of(unrelated.getId(), BigDecimal.ONE));

        assertThat(ticket.getTotal()).isEqualByComparingTo(engineVersion == 2 ? "987.36" : "976.47");
        assertThat(result.remainingBasketValue()).isEqualByComparingTo(engineVersion == 2 ? "986.15" : "975.26");
        assertThat(result.refundableAmount()).isEqualByComparingTo("1.21");
        assertThat(result.lostBenefits()).isZero();
        assertThat(result.taxAdjustments()).isEmpty();
    }

    @Test
    void partialPackReturnUsesOriginalRuleAndPriceEvenAfterCatalogChange() {
        var fixture = ticketWithoutPromotion(11, "0.50");
        var ticket = fixture.ticket();
        var line = fixture.productLine();
        var pack = new QuantityPricing(QuantityPricing.Mode.PACK,
                List.of(new QuantityPricing.Tier(10, new BigDecimal("3.00"))));
        line.assignQuantityPriceSnapshot(new QuantityPriceSnapshot(pack, new BigDecimal("0.50")));
        ticket.addLine(DocumentLine.quantityPrice(ticket, 2, "10 x 3", new BigDecimal("-2.00"),
                true, "IVA", new BigDecimal("21")));
        when(product.getDiscountType()).thenReturn(DiscountType.NONE);
        var result = service().value(ticket, Map.of(line.getId(), BigDecimal.ONE));
        assertThat(result.remainingBasketValue()).isEqualByComparingTo("3.00");
        assertThat(result.refundableAmount()).isEqualByComparingTo("0.50");
        assertThat(service().value(ticket, Map.of(line.getId(), new BigDecimal("11"))).refundableAmount())
                .isEqualByComparingTo("3.50");
        ticket.confirm("T-100", UUID.randomUUID(), java.time.Instant.now(), false);
        assertThat(TicketPrintView.from(ticket).lines()).hasSize(2);
        assertThat(DocumentLineCommand.from(line).toEntity(ticket).getQuantityPriceSnapshot())
                .isEqualTo(line.getQuantityPriceSnapshot());
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.atLeastOnce()).previewVersion2(any());
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.never()).preview(any());
    }

    @Test
    void consecutiveThresholdReturnsNeverReclaimPriorRefundAndFullReturnSettlesBalance() {
        var fixture = ticketWithoutPromotion(13, "1.00");
        var line = fixture.productLine();
        var ticket = fixture.ticket();
        var threshold = new QuantityPricing(QuantityPricing.Mode.UNIT_THRESHOLD,
                List.of(new QuantityPricing.Tier(12, new BigDecimal("0.80"))));
        line.assignQuantityPriceSnapshot(new QuantityPriceSnapshot(threshold, BigDecimal.ONE));
        ticket.addLine(DocumentLine.quantityPrice(ticket, 2, "12+", new BigDecimal("-2.60"),
                true, "IVA", new BigDecimal("21")));
        assertThat(service().value(ticket, Map.of(line.getId(), BigDecimal.ONE)).refundableAmount())
                .isEqualByComparingTo("0.80");
        when(documents.confirmedRefundedQuantity(line.getId())).thenReturn(BigDecimal.ONE);
        when(documents.confirmedReturnAmount(ticket.getId())).thenReturn(new BigDecimal("0.80"));
        assertThat(service().value(ticket, Map.of(line.getId(), BigDecimal.ONE)).refundableAmount())
                .isEqualByComparingTo("0.00");
        assertThat(service().value(ticket, Map.of(line.getId(), new BigDecimal("12"))).refundableAmount())
                .isEqualByComparingTo("9.60");
    }

    private TicketReturnValuationService service() {
        return new TicketReturnValuationService(
                documents, products, loyaltyLines, loyaltySettlements,
                promotions, targets, coupons, engine);
    }

    private void historicalPromotion(Promotion promotion) {
        when(promotions.findAllById(any())).thenReturn(List.of(promotion));
        when(targets.findByPromocionIdIn(any())).thenReturn(List.of(
                new PromotionTarget(
                        promotion.id(), PromotionTargetType.PRODUCT, productId)));
    }

    private Fixture ticketWithPromotion(
            int quantity,
            String unitPrice,
            String promotionDiscount,
            Promotion promotion) {
        var ticket = new CommercialDocument(
                storeId,
                UUID.randomUUID(),
                CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 2),
                UUID.randomUUID(),
                BigDecimal.ZERO);
        var productLine = new DocumentLine(
                ticket,
                productId,
                1,
                quantity,
                "P-1",
                "Producto",
                "GENERAL",
                new BigDecimal(unitPrice),
                BigDecimal.ZERO,
                true,
                "IVA",
                new BigDecimal("21.00"));
        ticket.addLine(productLine);
        ticket.addLine(DocumentLine.special(
                ticket,
                2,
                "PROMOCION " + promotion.name(),
                new BigDecimal(promotionDiscount).negate(),
                true,
                "IVA",
                new BigDecimal("21.00"),
                promotion.rootVersionId(),
                promotion.id(),
                null));
        return new Fixture(ticket, productLine);
    }

    private Fixture ticketWithoutPromotion(int quantity, String unitPrice) {
        var ticket = new CommercialDocument(
                storeId,
                UUID.randomUUID(),
                CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 2),
                UUID.randomUUID(),
                BigDecimal.ZERO);
        var productLine = new DocumentLine(
                ticket,
                productId,
                1,
                quantity,
                "P-1",
                "Producto",
                "GENERAL",
                new BigDecimal(unitPrice),
                BigDecimal.ZERO,
                true,
                "IVA",
                new BigDecimal("21.00"));
        ticket.addLine(productLine);
        return new Fixture(ticket, productLine);
    }

    private static Promotion buyXPayY(int buy, int pay) {
        var promotion = Promotion.draft(
                UUID.randomUUID(),
                buy + "x" + pay,
                PromotionType.BUY_X_PAY_Y,
                LocalDate.of(2026, 1, 1));
        promotion.configureBuyXPayY(
                BigDecimal.valueOf(buy), BigDecimal.valueOf(pay));
        return promotion;
    }

    private static Promotion secondUnitPercent(String percent) {
        var promotion = Promotion.draft(
                UUID.randomUUID(),
                "Segunda unidad",
                PromotionType.SECOND_UNIT_PERCENT,
                LocalDate.of(2026, 1, 1));
        promotion.configureSecondUnitPercent(new BigDecimal(percent));
        return promotion;
    }

    private record Fixture(
            CommercialDocument ticket,
            DocumentLine productLine) {
    }
}

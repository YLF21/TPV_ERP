package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.DiscountType;
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
import com.tpverp.backend.promotion.PromotionalCouponRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    private final PromotionEngine engine = new PromotionEngine();
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

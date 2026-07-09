package com.tpverp.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PromotionEngineTest {

    private final PromotionEngine engine = new PromotionEngine();

    @Test
    void buyXPayYDiscountsCheapestEligibleItem() {
        var productId = UUID.randomUUID();
        var promotion = buyXPayY("3x2 Agua", 3, 2);

        var preview = engine.preview(new PromotionEvaluationRequest(
                List.of(
                        line(1, productId, "2.00"),
                        line(2, productId, "1.00"),
                        line(3, productId, "3.00")),
                List.of(promotion),
                List.of(productTarget(promotion, productId))));

        assertThat(preview.discountTotal()).isEqualByComparingTo("1.00");
        assertThat(preview.appliedPromotions()).hasSize(1);
        assertThat(preview.appliedPromotions().getFirst().amount()).isEqualByComparingTo("1.00");
        assertThat(preview.appliedPromotions().getFirst().affectedPositions())
                .containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    void conflictingPromotionsKeepBestCustomerBenefit() {
        var productId = UUID.randomUUID();
        var buyXPayY = buyXPayY("3x2 Agua", 3, 2);
        var secondUnitPercent = secondUnitPercent("Segunda unidad Agua", new BigDecimal("25.00"));

        var preview = engine.preview(new PromotionEvaluationRequest(
                List.of(
                        line(1, productId, "2.00"),
                        line(2, productId, "1.00"),
                        line(3, productId, "3.00")),
                List.of(secondUnitPercent, buyXPayY),
                List.of(
                        productTarget(buyXPayY, productId),
                        productTarget(secondUnitPercent, productId))));

        assertThat(preview.discountTotal()).isEqualByComparingTo("1.00");
        assertThat(preview.appliedPromotions())
                .singleElement()
                .satisfies(benefit -> {
                    assertThat(benefit.promotionId()).isEqualTo(buyXPayY.id());
                    assertThat(benefit.affectedPositions()).containsExactlyInAnyOrder(1, 2, 3);
                    assertThat(benefit.amount()).isEqualByComparingTo("1.00");
                });
    }

    @Test
    void nonConflictingPromotionsAccumulate() {
        var waterId = UUID.randomUUID();
        var milkId = UUID.randomUUID();
        var waterPromo = buyXPayY("3x2 Agua", 3, 2);
        var milkPromo = secondUnitPercent("Segunda unidad Leche", new BigDecimal("50.00"));

        var preview = engine.preview(new PromotionEvaluationRequest(
                List.of(
                        line(1, waterId, "2.00"),
                        line(2, waterId, "1.00"),
                        line(3, waterId, "3.00"),
                        line(4, milkId, "2.00"),
                        line(5, milkId, "2.00")),
                List.of(waterPromo, milkPromo),
                List.of(
                        productTarget(waterPromo, waterId),
                        productTarget(milkPromo, milkId))));

        assertThat(preview.discountTotal()).isEqualByComparingTo("2.00");
        assertThat(preview.appliedPromotions()).hasSize(2);
        assertThat(preview.appliedPromotions())
                .extracting(PromotionBenefit::promotionId)
                .containsExactlyInAnyOrder(waterPromo.id(), milkPromo.id());
        assertThat(preview.appliedPromotions())
                .extracting(PromotionBenefit::affectedPositions)
                .containsExactlyInAnyOrder(Set.of(1, 2, 3), Set.of(4, 5));
    }

    private static Promotion buyXPayY(String name, int buy, int pay) {
        var promotion = Promotion.draft(
                UUID.randomUUID(), name, PromotionType.BUY_X_PAY_Y, LocalDate.of(2026, 7, 9));
        promotion.configureBuyXPayY(BigDecimal.valueOf(buy), BigDecimal.valueOf(pay));
        return promotion;
    }

    private static Promotion secondUnitPercent(String name, BigDecimal percent) {
        var promotion = Promotion.draft(
                UUID.randomUUID(), name, PromotionType.SECOND_UNIT_PERCENT, LocalDate.of(2026, 7, 9));
        promotion.configureSecondUnitPercent(percent);
        return promotion;
    }

    private static PromotionTarget productTarget(Promotion promotion, UUID productId) {
        return new PromotionTarget(promotion.id(), PromotionTargetType.PRODUCT, productId);
    }

    private static PromotionEvaluationLine line(int position, UUID productId, String unitPrice) {
        return new PromotionEvaluationLine(
                position,
                productId,
                null,
                null,
                BigDecimal.ONE,
                new BigDecimal(unitPrice),
                true,
                "IVA",
                new BigDecimal("21.00"),
                false,
                true);
    }
}

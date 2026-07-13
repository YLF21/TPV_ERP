package com.tpverp.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

    @Test
    void buyXPayYCanApplyTwiceToQuantityOnSameLine() {
        var productId = UUID.randomUUID();
        var promotion = buyXPayY("3x2 Agua", 3, 2);

        var preview = engine.preview(new PromotionEvaluationRequest(
                List.of(line(1, productId, null, null, "6", "1.00", "IVA")),
                List.of(promotion),
                List.of(productTarget(promotion, productId))));

        assertThat(preview.discountTotal()).isEqualByComparingTo("2.00");
        assertThat(preview.appliedPromotions()).hasSize(2);
        assertThat(preview.appliedPromotions())
                .extracting(PromotionBenefit::amount)
                .allSatisfy(amount -> assertThat(amount).isEqualByComparingTo("1.00"));
    }

    @Test
    void candidatesRemainTaxHomogeneousForMixedTaxTargets() {
        var familyId = UUID.randomUUID();
        var promotion = buyXPayY("3x2 Familia", 3, 2);
        var document = salesDocument();

        var preview = engine.preview(new PromotionEvaluationRequest(
                List.of(
                        line(1, UUID.randomUUID(), familyId, null, "3", "1.00", "IVA"),
                        line(2, UUID.randomUUID(), familyId, null, "3", "1.00", "IGIC")),
                List.of(promotion),
                List.of(new PromotionTarget(promotion.id(), PromotionTargetType.FAMILY, familyId))));
        var lines = engine.promotionLines(document, preview);

        assertThat(preview.discountTotal()).isEqualByComparingTo("2.00");
        assertThat(preview.appliedPromotions()).hasSize(2);
        assertThat(preview.appliedPromotions())
                .extracting(PromotionBenefit::taxRegime)
                .containsExactlyInAnyOrder("IVA", "IGIC");
        assertThat(lines)
                .extracting(com.tpverp.backend.document.DocumentLine::getRegimenImpuesto)
                .containsExactlyInAnyOrder("IVA", "IGIC");
    }

    @Test
    void exactSelectionKeepsBestTotalInsteadOfBestSingleCandidate() {
        var familyId = UUID.randomUUID();
        var productA = UUID.randomUUID();
        var productB = UUID.randomUUID();
        var familyPromo = secondUnitPercent("Familia", new BigDecimal("100.00"));
        var productAPromo = secondUnitPercent("Producto A", new BigDecimal("100.00"));
        var productBPromo = secondUnitPercent("Producto B", new BigDecimal("100.00"));

        var preview = engine.preview(new PromotionEvaluationRequest(
                List.of(
                        line(1, productA, familyId, null, "1", "5.00", "IVA"),
                        line(2, productA, null, null, "1", "3.00", "IVA"),
                        line(3, productB, familyId, null, "1", "5.00", "IVA"),
                        line(4, productB, null, null, "1", "3.00", "IVA")),
                List.of(familyPromo, productAPromo, productBPromo),
                List.of(
                        new PromotionTarget(familyPromo.id(), PromotionTargetType.FAMILY, familyId),
                        productTarget(productAPromo, productA),
                        productTarget(productBPromo, productB))));

        assertThat(preview.discountTotal()).isEqualByComparingTo("6.00");
        assertThat(preview.appliedPromotions())
                .extracting(PromotionBenefit::promotionId)
                .containsExactlyInAnyOrder(productAPromo.id(), productBPromo.id());
    }

    @Test
    void completeCandidateGenerationAllowsNonGreedyPairingToWin() {
        var familyId = UUID.randomUUID();
        var productB = UUID.randomUUID();
        var familyPromo = secondUnitPercent("Familia", new BigDecimal("100.00"));
        var productPromo = buyXPayY("2x1 Producto B", 2, 1);

        var preview = engine.preview(new PromotionEvaluationRequest(
                List.of(
                        line(1, UUID.randomUUID(), familyId, null, "1", "10.00", "IVA"),
                        line(2, productB, familyId, null, "1", "5.00", "IVA"),
                        line(3, UUID.randomUUID(), familyId, null, "1", "1.00", "IVA"),
                        line(4, productB, null, null, "1", "5.00", "IVA")),
                List.of(familyPromo, productPromo),
                List.of(
                        new PromotionTarget(familyPromo.id(), PromotionTargetType.FAMILY, familyId),
                        productTarget(productPromo, productB))));

        assertThat(preview.discountTotal()).isEqualByComparingTo("6.00");
        assertThat(preview.appliedPromotions())
                .extracting(PromotionBenefit::promotionId)
                .containsExactlyInAnyOrder(familyPromo.id(), productPromo.id());
    }

    @Test
    void completeCandidateGenerationAllowsNonGreedyBuyXPayYPackToWin() {
        var familyId = UUID.randomUUID();
        var productB = UUID.randomUUID();
        var familyPromo = buyXPayY("3x2 Familia", 3, 2);
        var productPromo = buyXPayY("2x1 Producto B", 2, 1);

        var preview = engine.preview(new PromotionEvaluationRequest(
                List.of(
                        line(1, UUID.randomUUID(), familyId, null, "1", "10.00", "IVA"),
                        line(2, productB, familyId, null, "1", "9.00", "IVA"),
                        line(3, UUID.randomUUID(), familyId, null, "1", "1.00", "IVA"),
                        line(4, productB, null, null, "1", "9.00", "IVA"),
                        line(5, UUID.randomUUID(), familyId, null, "1", "1.00", "IVA")),
                List.of(familyPromo, productPromo),
                List.of(
                        new PromotionTarget(familyPromo.id(), PromotionTargetType.FAMILY, familyId),
                        productTarget(productPromo, productB))));

        assertThat(preview.discountTotal()).isEqualByComparingTo("10.00");
        assertThat(preview.appliedPromotions())
                .extracting(PromotionBenefit::promotionId)
                .containsExactlyInAnyOrder(familyPromo.id(), productPromo.id());
    }

    @Test
    @Timeout(2)
    void largeCandidateSetFallsBackToDeterministicPreview() {
        var familyId = UUID.randomUUID();
        var promotion = secondUnitPercent("Segunda unidad Familia", new BigDecimal("50.00"));
        var lines = new ArrayList<PromotionEvaluationLine>();
        for (var position = 1; position <= 19; position++) {
            lines.add(line(position, UUID.randomUUID(), familyId, null, "1", "1.00", "IVA"));
        }

        var preview = engine.preview(new PromotionEvaluationRequest(
                lines,
                List.of(promotion),
                List.of(new PromotionTarget(promotion.id(), PromotionTargetType.FAMILY, familyId))));

        assertThat(preview.discountTotal()).isEqualByComparingTo("4.50");
        assertThat(preview.appliedPromotions()).hasSize(9);
    }

    @Test
    void purchaseThresholdDiscountAppliesConfiguredPercentage() {
        var promotion = Promotion.draft(
                UUID.randomUUID(), "10% desde 10", PromotionType.PURCHASE_THRESHOLD_DISCOUNT,
                LocalDate.of(2026, 7, 9));
        promotion.configurePurchaseThresholdDiscount(
                new BigDecimal("10.00"), null, new BigDecimal("10.00"), null);

        var preview = engine.preview(new PromotionEvaluationRequest(
                List.of(line(1, UUID.randomUUID(), null, null, "2", "5.00", "IVA")),
                List.of(promotion)));

        assertThat(preview.discountTotal()).isEqualByComparingTo("1.00");
    }

    @Test
    void purchaseThresholdUsesFinalTaxIncludedPayableAmount() {
        var promotion = Promotion.draft(
                UUID.randomUUID(), "10% desde 11", PromotionType.PURCHASE_THRESHOLD_DISCOUNT,
                LocalDate.of(2026, 7, 9));
        promotion.configurePurchaseThresholdDiscount(
                new BigDecimal("11.00"), null, new BigDecimal("10.00"), null);
        var line = new PromotionEvaluationLine(
                1, UUID.randomUUID(), null, null, BigDecimal.ONE,
                new BigDecimal("10.00"), false, "IVA", new BigDecimal("21.00"),
                false, true);

        var preview = engine.preview(new PromotionEvaluationRequest(
                List.of(line), List.of(promotion)));

        assertThat(preview.discountTotal()).isEqualByComparingTo("1.00");
    }

    @Test
    void fixedPackPriceDiscountsDifferenceAgainstPackTotal() {
        var promotion = Promotion.draft(
                UUID.randomUUID(), "Lote por 5", PromotionType.FIXED_PACK_PRICE,
                LocalDate.of(2026, 7, 9));
        promotion.configureFixedPackPrice(new BigDecimal("3"), new BigDecimal("5.00"));

        var preview = engine.preview(new PromotionEvaluationRequest(
                List.of(
                        line(1, UUID.randomUUID(), "2.00"),
                        line(2, UUID.randomUUID(), "2.00"),
                        line(3, UUID.randomUUID(), "3.00")),
                List.of(promotion)));

        assertThat(preview.discountTotal()).isEqualByComparingTo("2.00");
    }

    @Test
    void quantityDiscountSupportsDecimalQuantity() {
        var promotion = Promotion.draft(
                UUID.randomUUID(), "Volumen", PromotionType.QUANTITY_DISCOUNT,
                LocalDate.of(2026, 7, 9));
        promotion.configureQuantityDiscount(
                new BigDecimal("2.500"), null, new BigDecimal("20.00"), null);

        var preview = engine.preview(new PromotionEvaluationRequest(
                List.of(line(1, UUID.randomUUID(), null, null, "2.5", "4.00", "IVA")),
                List.of(promotion)));

        assertThat(preview.discountTotal()).isEqualByComparingTo("2.00");
    }

    @Test
    void buyXPayYSameProductDoesNotMixAndMixedTargetsDiscountsCheapest() {
        var familyId = UUID.randomUUID();
        var same = buyXPayY("2x1 mismo", 2, 1);
        same.configureBuyXPayY(new BigDecimal("2"), BigDecimal.ONE, BuyXPayYMode.SAME_PRODUCT);
        var mixed = buyXPayY("2x1 mezcla", 2, 1);
        mixed.configureBuyXPayY(new BigDecimal("2"), BigDecimal.ONE, BuyXPayYMode.MIXED_TARGETS);
        var lines = List.of(
                line(1, UUID.randomUUID(), familyId, null, "1", "5.00", "IVA"),
                line(2, UUID.randomUUID(), familyId, null, "1", "1.00", "IVA"));

        var samePreview = engine.preview(new PromotionEvaluationRequest(
                lines, List.of(same),
                List.of(new PromotionTarget(same.id(), PromotionTargetType.FAMILY, familyId))));
        var mixedPreview = engine.preview(new PromotionEvaluationRequest(
                lines, List.of(mixed),
                List.of(new PromotionTarget(mixed.id(), PromotionTargetType.FAMILY, familyId))));

        assertThat(samePreview.discountTotal()).isZero();
        assertThat(mixedPreview.discountTotal()).isEqualByComparingTo("1.00");
    }

    @Test
    void evaluationRequestRejectsNullElements() {
        var productId = UUID.randomUUID();
        var promotion = buyXPayY("3x2 Agua", 3, 2);

        assertThatThrownBy(() -> new PromotionEvaluationRequest(
                java.util.Arrays.asList(line(1, productId, "1.00"), null),
                List.of(promotion),
                List.of(productTarget(promotion, productId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lines");
        assertThatThrownBy(() -> new PromotionEvaluationRequest(
                List.of(line(1, productId, "1.00")),
                java.util.Arrays.asList(promotion, null),
                List.of(productTarget(promotion, productId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("promotions");
        assertThatThrownBy(() -> new PromotionEvaluationRequest(
                List.of(line(1, productId, "1.00")),
                List.of(promotion),
                java.util.Arrays.asList(productTarget(promotion, productId), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targets");
    }

    @Test
    void evaluationLineRejectsInvalidAmountsAndAcceptsDecimalQuantity() {
        var productId = UUID.randomUUID();

        assertThatThrownBy(() -> line(1, productId, null, null, "0", "1.00", "IVA"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
        assertThat(line(1, productId, null, null, "1.5", "1.00", "IVA").quantity())
                .isEqualByComparingTo("1.5");
        assertThatThrownBy(() -> line(1, productId, null, null, "1", "-1.00", "IVA"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unitPrice");
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
        return line(position, productId, null, null, "1", unitPrice, "IVA");
    }

    private static PromotionEvaluationLine line(
            int position,
            UUID productId,
            UUID familyId,
            UUID subfamilyId,
            String quantity,
            String unitPrice,
            String taxRegime) {
        return new PromotionEvaluationLine(
                position,
                productId,
                familyId,
                subfamilyId,
                new BigDecimal(quantity),
                new BigDecimal(unitPrice),
                true,
                taxRegime,
                new BigDecimal("21.00"),
                false,
                true);
    }

    private static CommercialDocument salesDocument() {
        return new CommercialDocument(
                UUID.randomUUID(),
                UUID.randomUUID(),
                CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 9),
                UUID.randomUUID(),
                BigDecimal.ZERO);
    }
}

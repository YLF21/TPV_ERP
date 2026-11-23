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
    void promotionLinesKeepTaxOfFreeUnitsForMixedTaxTargets() {
        var familyId = UUID.randomUUID();
        var promotion = buyXPayY("3x2 Familia", 3, 2);
        var document = salesDocument();

        var preview = engine.preview(new PromotionEvaluationRequest(
                List.of(
                        line(1, UUID.randomUUID(), familyId, null, "3", "3.00", "IVA"),
                        line(2, UUID.randomUUID(), familyId, null, "3", "1.00", "IGIC")),
                List.of(promotion),
                List.of(new PromotionTarget(promotion.id(), PromotionTargetType.FAMILY, familyId))));
        var lines = engine.promotionLines(document, preview);

        assertThat(preview.discountTotal()).isEqualByComparingTo("4.00");
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
    void sameProductCompletesRepeatedPacksAcrossLinesWithoutUsingOtherProductRemainders() {
        var product = UUID.randomUUID();
        var promotion = buyXPayY("3x2 mismo", 3, 2);
        promotion.configureBuyXPayY(new BigDecimal("3"), new BigDecimal("2"), BuyXPayYMode.SAME_PRODUCT);
        var preview = engine.preview(new PromotionEvaluationRequest(List.of(
                line(1, product, null, null, "2", "5.00", "IVA"),
                line(2, product, null, null, "5", "5.00", "IVA"),
                line(3, UUID.randomUUID(), null, null, "2", "1.00", "IVA")), List.of(promotion)));

        assertThat(preview.discountTotal()).isEqualByComparingTo("10.00");
        assertThat(preview.appliedPromotions()).hasSize(2)
                .allSatisfy(benefit -> assertThat(benefit.affectedPositions()).doesNotContain(3));
    }

    @Test
    void mixedTargetsRepeatPacksGivingAwayCheapestUnitOfEachPack() {
        var promotion = buyXPayY("3x2 mezcla", 3, 2);
        var preview = engine.preview(new PromotionEvaluationRequest(List.of(
                line(1, UUID.randomUUID(), "12.00"), line(2, UUID.randomUUID(), "10.00"),
                line(3, UUID.randomUUID(), "8.00"), line(4, UUID.randomUUID(), "6.00"),
                line(5, UUID.randomUUID(), "4.00"), line(6, UUID.randomUUID(), "2.00")), List.of(promotion)));

        assertThat(preview.discountTotal()).isEqualByComparingTo("10.00");
        assertThat(preview.appliedPromotions()).extracting(PromotionBenefit::amount)
                .containsExactlyInAnyOrder(new BigDecimal("8.00"), new BigDecimal("2.00"));
    }

    @Test
    void mixedTargetsCompletePackAcrossTaxRatesAndDiscountCheapestFinalPrice() {
        var promotion = buyXPayY("3x2 impuestos", 3, 2);
        var preview = engine.preview(new PromotionEvaluationRequest(List.of(
                taxLine(1, "2", "10.00", false, "21"),
                taxLine(2, "1", "11.00", true, "10")), List.of(promotion)));

        assertThat(preview.appliedPromotions()).singleElement().satisfies(benefit -> {
            assertThat(benefit.amount()).isEqualByComparingTo("11.00");
            assertThat(benefit.taxIncluded()).isTrue();
            assertThat(benefit.taxPercent()).isEqualByComparingTo("10");
            assertThat(benefit.affectedPositions()).containsExactlyInAnyOrder(1, 2);
        });
    }

    @Test
    void mixedTargetsSplitFreeUnitsByTheirTaxAndPreservePromotionIdentity() {
        var promotion = buyXPayY("4x2 impuestos", 4, 2);
        var preview = engine.preview(new PromotionEvaluationRequest(List.of(
                taxLine(1, "1", "15.00", true, "21"),
                taxLine(2, "1", "12.00", true, "21"),
                taxLine(3, "1", "8.00", false, "21"),
                taxLine(4, "1", "5.00", true, "10")), List.of(promotion)));

        assertThat(preview.appliedPromotions()).hasSize(2).allSatisfy(benefit -> {
            assertThat(benefit.promotionId()).isEqualTo(promotion.rootVersionId());
            assertThat(benefit.promotionVersionId()).isEqualTo(promotion.id());
            assertThat(benefit.affectedPositions()).containsExactlyInAnyOrder(1, 2, 3, 4);
        });
        assertThat(preview.appliedPromotions()).anySatisfy(benefit -> {
            assertThat(benefit.amount()).isEqualByComparingTo("8.00");
            assertThat(benefit.taxIncluded()).isFalse();
            assertThat(benefit.taxPercent()).isEqualByComparingTo("21");
        }).anySatisfy(benefit -> {
            assertThat(benefit.amount()).isEqualByComparingTo("5.00");
            assertThat(benefit.taxIncluded()).isTrue();
            assertThat(benefit.taxPercent()).isEqualByComparingTo("10");
        });
        assertThat(preview.discountTotal()).isEqualByComparingTo("13.00");
        assertThat(preview.payableDiscountTotal()).isEqualByComparingTo("14.68");
        assertThat(engine.promotionLines(salesDocument(), preview)).anySatisfy(item -> {
            assertThat(item.getTotal()).isEqualByComparingTo("-9.68");
            assertThat(item.getBase()).isEqualByComparingTo("-8.00");
            assertThat(item.getImpuesto()).isEqualByComparingTo("-1.68");
        }).anySatisfy(item -> {
            assertThat(item.getTotal()).isEqualByComparingTo("-5.00");
            assertThat(item.getBase()).isEqualByComparingTo("-4.55");
            assertThat(item.getImpuesto()).isEqualByComparingTo("-0.45");
        });
    }

    @Test
    void largeMixedTargetGroupsUseFinalPriceForRepeatedPacks() {
        var promotion = buyXPayY("3x2 muchos", 3, 2);
        var preview = engine.preview(new PromotionEvaluationRequest(List.of(
                taxLine(1, "14", "10.00", false, "21"),
                taxLine(2, "7", "11.00", true, "10")), List.of(promotion)));

        // Four packs at 12.10, one mixed pack at 11, two packs at 11.
        assertThat(preview.appliedPromotions()).hasSize(7);
        var document = salesDocument();
        var discount = engine.promotionLines(document, preview).stream()
                .map(com.tpverp.backend.document.DocumentLine::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(discount).isEqualByComparingTo("-81.40");
    }

    @Test
    void buyXPayZeroGivesAwayAllUnitsOfCompletePacks() {
        var promotion = buyXPayY("2x0", 2, 0);
        promotion.activate();
        var preview = engine.preview(new PromotionEvaluationRequest(List.of(
                line(1, UUID.randomUUID(), null, null, "3", "5.00", "IVA")), List.of(promotion)));

        assertThat(preview.discountTotal()).isEqualByComparingTo("10.00");
        assertThat(preview.appliedPromotions()).hasSize(1);
    }

    @Test
    @Timeout(5)
    void thousandsOfUnitsUseBoundedPackGeneration() {
        var promotion = buyXPayY("3x2 volumen", 3, 2);
        var preview = engine.preview(new PromotionEvaluationRequest(List.of(
                taxLine(1, "3000", "10.00", false, "21")), List.of(promotion)));

        assertThat(preview.appliedPromotions()).hasSize(1000);
        assertThat(preview.payableDiscountTotal()).isEqualByComparingTo("12100.00");
    }

    @Test
    void conflictingPromotionSelectionComparesFinalSavingsInsteadOfMixingNetAndGrossAmounts() {
        var product = UUID.randomUUID();
        var mixed = buyXPayY("2x1 combinado", 2, 1);
        var competitor = Promotion.draft(UUID.randomUUID(), "Descuento neto", PromotionType.QUANTITY_DISCOUNT,
                LocalDate.of(2026, 7, 9));
        competitor.configureQuantityDiscount(BigDecimal.ONE, new BigDecimal("9.50"), null, null);
        var preview = engine.preview(new PromotionEvaluationRequest(List.of(
                new PromotionEvaluationLine(1, product, null, null, BigDecimal.ONE,
                        new BigDecimal("10.00"), false, "IVA", new BigDecimal("21"), false, true),
                taxLine(2, "1", "11.00", true, "10")), List.of(mixed, competitor),
                List.of(productTarget(competitor, product))));

        // The competing 9.50 net discount saves 11.50 gross, beating the free 11.00 item.
        assertThat(preview.appliedPromotions()).singleElement().satisfies(benefit -> {
            assertThat(benefit.promotionId()).isEqualTo(competitor.rootVersionId());
            assertThat(benefit.amount()).isEqualByComparingTo("9.50");
        });
    }

    private static PromotionEvaluationLine taxLine(
            int position, String quantity, String price, boolean included, String percent) {
        return new PromotionEvaluationLine(position, UUID.randomUUID(), null, null,
                new BigDecimal(quantity), new BigDecimal(price), included, "IVA", new BigDecimal(percent),
                false, true);
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

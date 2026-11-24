package com.tpverp.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.catalog.QuantityPricing;
import com.tpverp.backend.document.QuantityPriceSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuantityPricingEngineTest {
    private final PromotionEngine engine = new PromotionEngine();
    private final UUID productId = UUID.randomUUID();

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(QuantityPricing.Mode.class)
    void disabledRulesNeverDiscountAndReactivationRestoresTheSameTiers(QuantityPricing.Mode mode) {
        var tiers = List.of(new QuantityPricing.Tier(3,
                new BigDecimal(mode == QuantityPricing.Mode.PACK ? "3" : "1")));
        var lines = List.of(line(1, "3", "2", true, false, true));
        var inactive = new QuantityPriceSnapshot(new QuantityPricing(mode, tiers, false), new BigDecimal("2"));
        var disabled = new PromotionEvaluationRequest(lines, List.of(), List.of(), Map.of(1, inactive));
        assertThat(engine.preview(disabled).appliedPromotions()).isEmpty();
        assertThat(engine.previewVersion2(disabled).appliedPromotions()).isEmpty();
        var competing = engine.preview(new PromotionEvaluationRequest(lines, List.of(buyXPayY(3, 2)),
                List.of(), Map.of(1, inactive)));
        assertThat(competing.discountTotal()).isEqualByComparingTo("2");
        assertThat(competing.appliedPromotions()).noneMatch(PromotionBenefit::quantityPrice);
        var active = new QuantityPriceSnapshot(new QuantityPricing(mode, inactive.rule().tiers(), true), inactive.salePrice());
        var enabled = engine.preview(new PromotionEvaluationRequest(lines, List.of(buyXPayY(3, 2)),
                List.of(), Map.of(1, active)));
        assertThat(enabled.discountTotal()).isEqualByComparingTo("3");
        assertThat(enabled.appliedPromotions()).singleElement().satisfies(benefit ->
                assertThat(benefit.quantityPrice()).isTrue());
    }

    @Test
    void splitRowsOfSameProductReachThePackTogether() {
        var snapshot = pack(3, "5");
        var preview = engine.preview(new PromotionEvaluationRequest(
                List.of(line(1, "1", "2", true, false, true), line(2, "2", "2", true, false, true)),
                List.of(), List.of(), Map.of(1, snapshot, 2, snapshot)));
        assertThat(preview.discountTotal()).isEqualByComparingTo("1.00");
        assertThat(preview.appliedPromotions()).singleElement().satisfies(benefit -> {
            assertThat(benefit.quantityPrice()).isTrue();
            assertThat(benefit.promotionId()).isNull();
            assertThat(benefit.affectedPositions()).containsExactlyInAnyOrder(1, 2);
        });
    }

    @Test
    void bestEligibleTariffCanBeatQuantityPrice() {
        var preview = engine.preview(new PromotionEvaluationRequest(
                List.of(line(1, "3", "1.50", true, false, true)),
                List.of(), List.of(), Map.of(1, pack(3, "5"))));
        assertThat(preview.appliedPromotions()).isEmpty();
    }

    @Test
    void promotionsAndQuantityPriceCompeteOnTheCompleteFinalSaving() {
        var promotion = buyXPayY(3, 2);
        var lines = List.of(line(1, "3", "2", true, false, true));
        var promotionWins = engine.preview(new PromotionEvaluationRequest(lines, List.of(promotion),
                List.of(), Map.of(1, pack(3, "5"))));
        assertThat(promotionWins.discountTotal()).isEqualByComparingTo("2.00");
        assertThat(promotionWins.appliedPromotions()).singleElement()
                .satisfies(benefit -> assertThat(benefit.quantityPrice()).isFalse());
        var quantityWins = engine.preview(new PromotionEvaluationRequest(lines, List.of(promotion),
                List.of(), Map.of(1, pack(3, "3"))));
        assertThat(quantityWins.discountTotal()).isEqualByComparingTo("3.00");
        assertThat(quantityWins.appliedPromotions()).singleElement()
                .satisfies(benefit -> assertThat(benefit.quantityPrice()).isTrue());
    }

    @Test
    void multiplePromotionalPacksCanTogetherBeatOneQuantityCandidate() {
        var promotion = buyXPayY(2, 1);
        var preview = engine.preview(new PromotionEvaluationRequest(
                List.of(line(1, "60", "2", true, false, true)), List.of(promotion), List.of(),
                Map.of(1, pack(60, "70"))));
        assertThat(preview.discountTotal()).isEqualByComparingTo("60.00");
        assertThat(preview.appliedPromotions()).noneMatch(PromotionBenefit::quantityPrice);
    }

    @Test
    void lockedAndManuallyDiscountedRowsCannotHelpReachAThreshold() {
        var snapshot = pack(3, "4");
        var preview = engine.preview(new PromotionEvaluationRequest(List.of(
                line(1, "1", "2", true, false, true),
                line(2, "1", "2", true, true, true),
                line(3, "1", "2", true, false, false)),
                List.of(), List.of(), Map.of(1, snapshot, 2, snapshot, 3, snapshot)));
        assertThat(preview.appliedPromotions()).isEmpty();
    }

    @Test
    void taxExclusiveSavingsRetainTheirTaxConvention() {
        var preview = engine.preview(new PromotionEvaluationRequest(
                List.of(line(1, "3", "2", false, false, true)),
                List.of(), List.of(), Map.of(1, pack(3, "5"))));
        assertThat(preview.discountTotal()).isEqualByComparingTo("1.00");
        assertThat(preview.payableDiscountTotal()).isEqualByComparingTo("1.21");
    }

    @Test
    void taxExclusivePackTotalCorrectsSplitRowTaxRoundingExactly() {
        var snapshot = new QuantityPriceSnapshot(new QuantityPricing(QuantityPricing.Mode.PACK,
                List.of(new QuantityPricing.Tier(3, new BigDecimal("0.05")))), new BigDecimal("0.03"));
        var preview = engine.preview(new PromotionEvaluationRequest(List.of(
                line(1, "1", "0.03", false, false, true),
                line(2, "1", "0.03", false, false, true),
                line(3, "1", "0.03", false, false, true)), List.of(), List.of(),
                Map.of(1, snapshot, 2, snapshot, 3, snapshot)));
        // Three 0.03 net rows each carry 0.01 tax (0.12 payable). The pack is
        // 0.05 net + 0.01 tax = 0.06, requiring 0.04 base + 0.02 tax reduction.
        assertThat(preview.discountTotal()).isEqualByComparingTo("0.04");
        assertThat(preview.payableDiscountTotal()).isEqualByComparingTo("0.06");
        assertThat(preview.appliedPromotions()).singleElement().satisfies(benefit -> {
            assertThat(benefit.quantityAmounts().base()).isEqualByComparingTo("0.04");
            assertThat(benefit.quantityAmounts().tax()).isEqualByComparingTo("0.02");
        });
    }

    @Test
    void largerConflictComponentFindsTheExactOptimumBeyondGreedyAndSingleExchanges() {
        var products = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var lines = new ArrayList<PromotionEvaluationLine>();
        var snapshots = new HashMap<Integer, QuantityPriceSnapshot>();
        for (int index = 0; index < products.size(); index++) {
            lines.add(new PromotionEvaluationLine(index + 1, products.get(index), null, null,
                    BigDecimal.ONE, new BigDecimal("100"), true, "IVA", new BigDecimal("21"), false, true));
            snapshots.put(index + 1, new QuantityPriceSnapshot(new QuantityPricing(QuantityPricing.Mode.PACK,
                    List.of(new QuantityPricing.Tier(1, new BigDecimal("99")))), new BigDecimal("100")));
        }
        var promotions = new ArrayList<Promotion>();
        var targets = new ArrayList<PromotionTarget>();
        addThreshold(promotions, targets, "Lure", "11", products.get(1), products.get(2));
        addThreshold(promotions, targets, "Left", "10", products.get(0), products.get(1));
        addThreshold(promotions, targets, "Right", "10", products.get(2), products.get(3));
        for (int index = 0; index < 19; index++) {
            addThreshold(promotions, targets, "Dominated " + index, "0.50", products.get(1), products.get(2));
        }
        var preview = engine.preview(new PromotionEvaluationRequest(lines, promotions, targets, snapshots));
        // Every greedy order starts at Lure + the two outer quantity savings = 13;
        // neither Left nor Right alone can displace it. Their combination saves 20.
        assertThat(preview.discountTotal()).isEqualByComparingTo("20.00");
        assertThat(preview.appliedPromotions()).extracting(PromotionBenefit::name)
                .containsExactlyInAnyOrder("Left", "Right");
    }

    private void addThreshold(List<Promotion> promotions, List<PromotionTarget> targets,
            String name, String discount, UUID... products) {
        var promotion = Promotion.draft(UUID.randomUUID(), name, PromotionType.QUANTITY_DISCOUNT,
                LocalDate.of(2026, 11, 23));
        promotion.configureQuantityDiscount(BigDecimal.ONE, new BigDecimal(discount), null, null);
        promotions.add(promotion);
        for (var product : products) targets.add(new PromotionTarget(promotion.id(), PromotionTargetType.PRODUCT, product));
    }

    private QuantityPriceSnapshot pack(int quantity, String price) {
        return new QuantityPriceSnapshot(new QuantityPricing(QuantityPricing.Mode.PACK,
                List.of(new QuantityPricing.Tier(quantity, new BigDecimal(price)))), new BigDecimal("2"));
    }

    private PromotionEvaluationLine line(int position, String quantity, String price,
            boolean included, boolean manual, boolean discountable) {
        return new PromotionEvaluationLine(position, productId, null, null, new BigDecimal(quantity),
                new BigDecimal(price), included, "IVA", new BigDecimal("21"), manual, discountable);
    }

    private Promotion buyXPayY(int buy, int pay) {
        var promotion = Promotion.draft(UUID.randomUUID(), "Promocion", PromotionType.BUY_X_PAY_Y,
                LocalDate.of(2026, 11, 23));
        promotion.configureBuyXPayY(BigDecimal.valueOf(buy), BigDecimal.valueOf(pay));
        return promotion;
    }
}

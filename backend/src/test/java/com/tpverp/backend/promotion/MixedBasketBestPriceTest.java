package com.tpverp.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.catalog.QuantityPricing;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentLine;
import com.tpverp.backend.document.QuantityPriceSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class MixedBasketBestPriceTest {
    private static final LocalDate DATE = LocalDate.of(2026, 11, 23);
    private final PromotionEngine engine = new PromotionEngine();

    @ParameterizedTest
    @CsvSource({"8,PACK", "19,PACK", "10003,PACK", "8,UNIT_THRESHOLD",
            "19,UNIT_THRESHOLD", "10003,UNIT_THRESHOLD"})
    @Timeout(15)
    void familyPacksCanLeaveTheCompetingProductForItsQuantityPrice(int familyUnits, QuantityPricing.Mode mode) {
        var family = UUID.randomUUID();
        var product = UUID.randomUUID();
        var promotion = buyThreePayTwo();
        var lines = basket(family, product, familyUnits, true);
        var snapshot = quantityPrice(mode, 2, mode == QuantityPricing.Mode.PACK ? "9" : "4.5", "9");
        var request = new PromotionEvaluationRequest(lines, List.of(promotion),
                List.of(familyTarget(promotion, family)), Map.of(2, snapshot, 4, snapshot));

        var preview = engine.preview(request);

        var baseline = lines.stream().map(line -> line.unitPrice().multiply(line.quantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var previousPayable = baseline.subtract(engine.previewVersion2(request).payableDiscountTotal());
        var bestPayable = baseline.subtract(preview.payableDiscountTotal());
        assertThat(previousPayable).as("version 2 charged more for the same mixed basket")
                .isGreaterThan(bestPayable);

        // Every family pack saves exactly 1. Reserving B's two units saves 9;
        // the remaining family has N-1 units, enough for floor((N-1)/3) packs.
        assertThat(preview.payableDiscountTotal()).isEqualByComparingTo(BigDecimal.valueOf(9 + (familyUnits - 1) / 3));
        assertThat(preview.appliedPromotions()).filteredOn(PromotionBenefit::quantityPrice).singleElement()
                .satisfies(benefit -> assertThat(benefit.affectedPositions()).containsExactlyInAnyOrder(2, 4));
        assertThat(preview.appliedPromotions()).filteredOn(benefit -> !benefit.quantityPrice())
                .allSatisfy(benefit -> assertThat(benefit.affectedPositions()).doesNotContain(2, 4));
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 19, 10003})
    @Timeout(15)
    void interactingPromotionsRetainAlternativePacksBeyondTheCandidateAndUnitLimits(int familyUnits) {
        var family = UUID.randomUUID();
        var product = UUID.randomUUID();
        var familyPromotion = buyThreePayTwo();
        var productPromotion = draft("Product 2x1", PromotionType.BUY_X_PAY_Y);
        productPromotion.configureBuyXPayY(BigDecimal.valueOf(2), BigDecimal.ONE);
        // A real competing quantity offer saves 8, below the product 2x1's 9.
        var snapshot = quantityPrice(QuantityPricing.Mode.PACK, 2, "10", "9");

        var preview = engine.preview(new PromotionEvaluationRequest(basket(family, product, familyUnits, true),
                List.of(familyPromotion, productPromotion), List.of(familyTarget(familyPromotion, family),
                new PromotionTarget(productPromotion.id(), PromotionTargetType.PRODUCT, product)),
                Map.of(2, snapshot, 4, snapshot)));

        assertThat(preview.payableDiscountTotal()).isEqualByComparingTo(BigDecimal.valueOf(9 + (familyUnits - 1) / 3));
        assertThat(preview.appliedPromotions()).extracting(PromotionBenefit::promotionId)
                .contains(familyPromotion.id(), productPromotion.id());
    }

    @Test
    @Timeout(15)
    void aSingleRowAboveTenThousandUnitsStillReceivesEveryCompletePack() {
        var promotion = buyThreePayTwo();
        // The competing quantity price saves 3334; repeated 3x2 saves 6668.
        var snapshot = quantityPrice(QuantityPricing.Mode.PACK, 3, "5", "2");
        var preview = engine.preview(new PromotionEvaluationRequest(List.of(
                line(1, UUID.randomUUID(), null, "10002", "2", true)), List.of(promotion), List.of(),
                Map.of(1, snapshot)));

        assertThat(preview.payableDiscountTotal()).isEqualByComparingTo("6668.00");
    }

    @ParameterizedTest
    @EnumSource(QuantityPricing.Mode.class)
    void mixedPromotionTypesAgreeWithIndependentExhaustiveUnitAllocation(QuantityPricing.Mode mode) {
        var random = new Random(73109);
        for (int sample = 0; sample < 8; sample++) {
            var family = UUID.randomUUID();
            var product = UUID.randomUUID();
            var lines = new ArrayList<PromotionEvaluationLine>();
            var cents = new int[8];
            for (int unit = 0; unit < cents.length; unit++) {
                cents[unit] = 100 + random.nextInt(900);
                lines.add(line(unit + 1, unit == 1 ? product : UUID.randomUUID(), family, "1",
                        BigDecimal.valueOf(cents[unit], 2).toPlainString(), true));
            }
            lines.add(line(9, product, null, "1", BigDecimal.valueOf(cents[1], 2).toPlainString(), true));
            var buy = buyThreePayTwo();
            var second = draft("Second unit 50%", PromotionType.SECOND_UNIT_PERCENT);
            second.configureSecondUnitPercent(new BigDecimal("50"));
            var fixed = draft("Two for 5", PromotionType.FIXED_PACK_PRICE);
            fixed.configureFixedPackPrice(BigDecimal.valueOf(2), new BigDecimal("5"));
            var promotions = List.of(buy, second, fixed);
            var targets = promotions.stream().map(promotion -> familyTarget(promotion, family)).toList();
            var sale = BigDecimal.valueOf(cents[1], 2);
            var tier = mode == QuantityPricing.Mode.PACK ? sale : sale.divide(BigDecimal.valueOf(2));
            var snapshot = quantityPrice(mode, 2, tier.toPlainString(), sale.toPlainString());
            var expected = BigDecimal.valueOf(exhaustiveBestSaving(cents), 2);

            var preview = engine.preview(new PromotionEvaluationRequest(lines, promotions, targets,
                    Map.of(2, snapshot, 9, snapshot)));
            assertThat(preview.payableDiscountTotal()).as("mode %s, sample %s", mode, sample)
                    .isEqualByComparingTo(expected);
            Collections.reverse(lines);
            var reversedPromotions = new ArrayList<>(promotions);
            Collections.reverse(reversedPromotions);
            assertThat(engine.preview(new PromotionEvaluationRequest(lines, reversedPromotions, targets,
                    Map.of(2, snapshot, 9, snapshot))).payableDiscountTotal())
                    .as("reversed mode %s, sample %s", mode, sample).isEqualByComparingTo(expected);
        }
    }

    @Test
    void theChosenCombinationPreservesCentExactTaxExclusiveDocumentDiscounts() {
        var family = UUID.randomUUID();
        var product = UUID.randomUUID();
        var promotion = buyThreePayTwo();
        var snapshot = quantityPrice(QuantityPricing.Mode.PACK, 2, "9.01", "9");
        var preview = engine.preview(new PromotionEvaluationRequest(basket(family, product, 19, false),
                List.of(promotion), List.of(familyTarget(promotion, family)), Map.of(2, snapshot, 4, snapshot)));

        // Six family packs save 1.21 each. B saves 8.99 net and 1.89 tax.
        assertThat(preview.discountTotal()).isEqualByComparingTo("14.99");
        assertThat(preview.payableDiscountTotal()).isEqualByComparingTo("18.14");
        var document = new CommercialDocument(UUID.randomUUID(), UUID.randomUUID(),
                CommercialDocumentType.TICKET, DATE, UUID.randomUUID(), BigDecimal.ZERO);
        var discountLines = engine.promotionLines(document, preview);
        assertThat(discountLines.stream().map(DocumentLine::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("-18.14");
        assertThat(discountLines.stream().map(DocumentLine::getBase).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("-14.99");
        assertThat(discountLines.stream().map(DocumentLine::getImpuesto).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("-3.15");
    }

    @ParameterizedTest
    @CsvSource({"PACK,5,1.50,false", "UNIT_THRESHOLD,1.5,1.75,true"})
    void fractionalRowsUseQuantityPricingOrDecimalThresholdButDoNotBecomeWholeUnitPacks(
            QuantityPricing.Mode mode, String tier, String expectedSaving, boolean quantityWins) {
        var product = UUID.randomUUID();
        var threshold = draft("Decimal quantity discount", PromotionType.QUANTITY_DISCOUNT);
        threshold.configureQuantityDiscount(new BigDecimal("3.5"), new BigDecimal("1.5"), null, null);
        var snapshot = quantityPrice(mode, 3, tier, "2");

        var preview = engine.preview(new PromotionEvaluationRequest(List.of(line(1, product, null, "3.5", "2", true)),
                List.of(buyThreePayTwo(), threshold), List.of(), Map.of(1, snapshot)));

        assertThat(preview.payableDiscountTotal()).isEqualByComparingTo(expectedSaving);
        assertThat(preview.appliedPromotions()).singleElement()
                .satisfies(benefit -> assertThat(benefit.quantityPrice()).isEqualTo(quantityWins));
    }

    // Exhaustive set allocation over nine actual units. This oracle neither calls
    // the production optimizer nor copies its candidate limits/grouping strategy.
    // All prices are gross cents; a 50% discount rounds HALF_UP per application.
    private static int exhaustiveBestSaving(int[] prices) {
        var offers = new ArrayList<OracleOffer>();
        for (int mask = 1; mask < (1 << prices.length); mask++) {
            int count = Integer.bitCount(mask);
            if (count != 2 && count != 3) continue;
            int total = 0;
            int cheapest = Integer.MAX_VALUE;
            for (int unit = 0; unit < prices.length; unit++) {
                if ((mask & (1 << unit)) == 0) continue;
                total += prices[unit];
                cheapest = Math.min(cheapest, prices[unit]);
            }
            int saving = count == 3 ? cheapest : Math.max((cheapest + 1) / 2, total - 500);
            offers.add(new OracleOffer(mask, saving));
        }
        offers.add(new OracleOffer((1 << 1) | (1 << 8), prices[1]));
        var best = new int[1 << 9];
        for (int available = 1; available < best.length; available++) {
            for (var offer : offers) {
                if ((available & offer.units()) == offer.units()) {
                    best[available] = Math.max(best[available], offer.saving() + best[available ^ offer.units()]);
                }
            }
        }
        return best[best.length - 1];
    }

    @Test
    void equalPayableUnitPricesChooseTheMoreFavourableLegalTaxRounding() {
        var promotion = draft("2x1", PromotionType.BUY_X_PAY_Y);
        promotion.configureBuyXPayY(BigDecimal.valueOf(2), BigDecimal.ONE);
        var gross = line(1, UUID.randomUUID(), null, "1", "0.03", true);
        var net = new PromotionEvaluationLine(2, UUID.randomUUID(), null, null, BigDecimal.ONE,
                new BigDecimal("0.025"), false, "IVA", new BigDecimal("20"), false, true);
        var rule = quantityPrice(QuantityPricing.Mode.PACK, 1, "0.02", "0.03");
        for (var lines : List.of(List.of(gross, net), List.of(net, gross))) {
            var preview = engine.preview(new PromotionEvaluationRequest(lines, List.of(promotion), List.of(), Map.of(1, rule)));
            // Both units cost exactly .03 before fiscal rounding. Gifting the net unit
            // saves .03 base + .01 VAT, whereas gifting the tax-included one saves .03.
            assertThat(preview.payableDiscountTotal()).isEqualByComparingTo("0.04");
            assertThat(preview.appliedPromotions()).singleElement()
                    .satisfies(benefit -> assertThat(benefit.taxIncluded()).isFalse());
        }
    }

    @Test
    void repeatedNetPacksKeepTheTaxRoundedOnEachAward() {
        var promotion = draft("Second unit 50%", PromotionType.SECOND_UNIT_PERCENT);
        promotion.configureSecondUnitPercent(new BigDecimal("50"));
        var rule = quantityPrice(QuantityPricing.Mode.PACK, 10002, "599.00", "0.06");
        var preview = engine.preview(new PromotionEvaluationRequest(
                List.of(line(1, UUID.randomUUID(), null, "10002", "0.06", false)), List.of(promotion),
                List.of(), Map.of(1, rule)));
        assertThat(preview.appliedPromotions()).hasSize(1);
        // Each pair saves .03 net + .01 VAT; taxing the combined net amount would lose cents.
        assertThat(preview.discountTotal()).isEqualByComparingTo("150.03");
        assertThat(preview.payableDiscountTotal()).isEqualByComparingTo("200.04");
        var document = new CommercialDocument(UUID.randomUUID(), UUID.randomUUID(),
                CommercialDocumentType.TICKET, DATE, UUID.randomUUID(), BigDecimal.ZERO);
        var adjustment = engine.promotionLines(document, preview).getFirst();
        assertThat(adjustment.getBase()).isEqualByComparingTo("-150.03");
        assertThat(adjustment.getImpuesto()).isEqualByComparingTo("-50.01");
        assertThat(adjustment.getTotal()).isEqualByComparingTo("-200.04");
    }

    private record OracleOffer(int units, int saving) { }

    private static List<PromotionEvaluationLine> basket(UUID family, UUID product, int familyUnits, boolean included) {
        return List.of(line(1, UUID.randomUUID(), family, "1", "10", included),
                line(2, product, family, "1", "9", included),
                line(3, UUID.randomUUID(), family, Integer.toString(familyUnits - 2), "1", included),
                line(4, product, null, "1", "9", included));
    }

    private static PromotionEvaluationLine line(int position, UUID product, UUID family,
            String quantity, String price, boolean included) {
        return new PromotionEvaluationLine(position, product, family, null, new BigDecimal(quantity),
                new BigDecimal(price), included, "IVA", new BigDecimal("21"), false, true);
    }

    private static QuantityPriceSnapshot quantityPrice(QuantityPricing.Mode mode, int minimum, String price, String sale) {
        return new QuantityPriceSnapshot(new QuantityPricing(mode,
                List.of(new QuantityPricing.Tier(minimum, new BigDecimal(price)))), new BigDecimal(sale));
    }

    private static Promotion buyThreePayTwo() {
        var promotion = draft("Family 3x2", PromotionType.BUY_X_PAY_Y);
        promotion.configureBuyXPayY(new BigDecimal("3"), BigDecimal.valueOf(2));
        return promotion;
    }

    private static Promotion draft(String name, PromotionType type) {
        return Promotion.draft(UUID.randomUUID(), name, type, DATE);
    }

    private static PromotionTarget familyTarget(Promotion promotion, UUID family) {
        return new PromotionTarget(promotion.id(), PromotionTargetType.FAMILY, family);
    }
}

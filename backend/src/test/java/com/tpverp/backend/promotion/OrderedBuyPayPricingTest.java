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
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrderedBuyPayPricingTest {
    private final PromotionEngine engine = new PromotionEngine();

    @Test
    @Timeout(5)
    void thousandsOfDistinctSkuAndPricesUseTheCertifiedOrderingInTheAuthoritativeEngine() {
        int skuCount = 2400;
        var lines = new ArrayList<PromotionEvaluationLine>();
        for (int index = 1; index <= skuCount; index++) {
            lines.add(line(index, UUID.randomUUID(), BigDecimal.ONE, BigDecimal.valueOf(index, 2), true, "IVA", "21"));
        }
        var request = new PromotionEvaluationRequest(lines, List.of(promotion(BuyXPayYMode.MIXED_TARGETS)), List.of(),
                Map.of(skuCount, pack(1, "23.99", "24")));
        var fast = OrderedBuyPayPricing.tryPreview(request, quantityBenefits(request));

        // Free prices are 2398, 2395, ..., 1 cents: an arithmetic progression.
        // Reserving the top SKU for a one-cent quantity saving loses 800 cents
        // of promotional saving, so the unreserved progression is optimal.
        var expected = BigDecimal.valueOf((long) (skuCount / 3) * (skuCount - 1) / 2, 2);
        assertThat(fast).isNotNull();
        assertThat(fast.payableDiscountTotal()).isEqualByComparingTo(expected);
        assertThat(fast.appliedPromotions()).noneMatch(PromotionBenefit::quantityPrice);
        assertThat(engine.preview(request)).isEqualTo(fast);
    }

    @Test
    @Timeout(3)
    void billionUnitRowIsBatchedWithoutChangingPerPackTaxRounding() {
        var request = new PromotionEvaluationRequest(List.of(line(1, UUID.randomUUID(), new BigDecimal("1000000002"),
                new BigDecimal("0.03"), true, "IVA", "21")), List.of(promotion(BuyXPayYMode.MIXED_TARGETS)),
                List.of(), Map.of(1, pack(3, "0.08", "0.03")));

        var fast = OrderedBuyPayPricing.tryPreview(request, quantityBenefits(request));

        assertThat(fast).isNotNull();
        assertThat(fast.payableDiscountTotal()).isEqualByComparingTo("10000000.02");
        assertThat(fast.appliedPromotions()).singleElement().satisfies(benefit -> {
            assertThat(benefit.affectedPositions()).containsExactly(1);
            assertThat(benefit.quantityAmounts().base()).isEqualByComparingTo("6666666.68");
            assertThat(benefit.quantityAmounts().tax()).isEqualByComparingTo("3333333.34");
            assertThat(benefit.quantityAmounts().total()).isEqualByComparingTo("10000000.02");
        });
        assertThat(engine.preview(request)).isEqualTo(fast);
    }

    @ParameterizedTest
    @EnumSource(BuyXPayYMode.class)
    void orderedSolutionMatchesIndependentUnitSubsetOracleIncludingQuantityRateChoices(BuyXPayYMode mode) {
        var random = new Random(45191);
        for (int sample = 0; sample < 16; sample++) {
            var products = List.of(UUID.randomUUID(), UUID.randomUUID());
            var lines = new ArrayList<PromotionEvaluationLine>();
            var prices = new int[8];
            for (int index = 0; index < prices.length; index++) {
                prices[index] = 100 + random.nextInt(900);
                lines.add(line(index + 1, products.get(index / 4), BigDecimal.ONE,
                        BigDecimal.valueOf(prices[index], 2), true, index % 2 == 0 ? "IVA" : "IGIC",
                        index % 2 == 0 ? "21" : "7"));
            }
            // Two tax groups of product A each have a complete two-unit rate.
            // Their savings are chosen independently by the oracle, including
            // cases where taking either, both, or neither blocks valuable packs.
            var snapshots = new HashMap<Integer, QuantityPriceSnapshot>();
            int firstSaving = 1 + random.nextInt(500);
            int secondSaving = 1 + random.nextInt(500);
            firstSaving = Math.min(firstSaving, prices[0] + prices[2] - 1);
            secondSaving = Math.min(secondSaving, prices[1] + prices[3] - 1);
            var first = pack(2, BigDecimal.valueOf(prices[0] + prices[2] - firstSaving, 2).toPlainString(), "20");
            var second = pack(2, BigDecimal.valueOf(prices[1] + prices[3] - secondSaving, 2).toPlainString(), "20");
            snapshots.put(1, first);
            snapshots.put(3, first);
            snapshots.put(2, second);
            snapshots.put(4, second);
            var request = new PromotionEvaluationRequest(lines, List.of(promotion(mode)), List.of(), snapshots);
            var fast = OrderedBuyPayPricing.tryPreview(request, quantityBenefits(request));
            var expected = BigDecimal.valueOf(oracle(prices, mode, firstSaving, secondSaving), 2);

            assertThat(fast).isNotNull();
            assertThat(fast.payableDiscountTotal()).as("mode %s, sample %s", mode, sample)
                    .isEqualByComparingTo(expected);
            assertThat(engine.preview(request)).isEqualTo(fast);
        }
    }

    @Test
    void splitRowsAndDifferentIncludedTaxesCarryThePositionsOfTheirActualPacks() {
        var product = UUID.randomUUID();
        var request = new PromotionEvaluationRequest(List.of(
                line(1, product, new BigDecimal("2"), new BigDecimal("3"), true, "IVA", "21"),
                line(2, product, new BigDecimal("5"), new BigDecimal("2"), true, "IGIC", "7"),
                line(3, product, new BigDecimal("2"), BigDecimal.ONE, true, "IVA", "10")),
                List.of(promotion(BuyXPayYMode.MIXED_TARGETS)), List.of(), Map.of(1, pack(2, "5.99", "3")));

        var fast = OrderedBuyPayPricing.tryPreview(request, quantityBenefits(request));

        assertThat(fast).isNotNull();
        assertThat(fast.payableDiscountTotal()).isEqualByComparingTo("5.00");
        assertThat(fast.appliedPromotions()).hasSize(3).anySatisfy(benefit -> {
            assertThat(benefit.affectedPositions()).containsExactlyInAnyOrder(1, 2);
            assertThat(benefit.amount()).isEqualByComparingTo("2");
            assertThat(benefit.taxRegime()).isEqualTo("IGIC");
        }).anySatisfy(benefit -> {
            assertThat(benefit.affectedPositions()).containsExactly(2);
            assertThat(benefit.amount()).isEqualByComparingTo("2");
        }).anySatisfy(benefit -> {
            assertThat(benefit.affectedPositions()).containsExactlyInAnyOrder(2, 3);
            assertThat(benefit.amount()).isEqualByComparingTo("1");
            assertThat(benefit.taxPercent()).isEqualByComparingTo("10");
        });
    }

    @Test
    void unsupportedTaxPrecisionAndPromotionShapesDeferToTheGeneralSolver() {
        var product = UUID.randomUUID();
        var promotion = promotion(BuyXPayYMode.MIXED_TARGETS);
        var snapshots = Map.of(1, pack(3, "5", "2"));
        var net = new PromotionEvaluationRequest(List.of(line(1, product, new BigDecimal("3"), new BigDecimal("2"),
                false, "IVA", "21")), List.of(promotion), List.of(), snapshots);
        assertThat(OrderedBuyPayPricing.tryPreview(net, quantityBenefits(net))).isNull();
        var precision = new PromotionEvaluationRequest(List.of(line(1, product, new BigDecimal("3"),
                new BigDecimal("2.001"), true, "IVA", "21")), List.of(promotion), List.of(), snapshots);
        assertThat(OrderedBuyPayPricing.tryPreview(precision, quantityBenefits(precision))).isNull();
        var lines = List.of(line(1, product, new BigDecimal("3"), new BigDecimal("2"), true, "IVA", "21"));
        promotion.configureBuyXPayY(new BigDecimal("3"), BigDecimal.ONE);
        var multipleFree = new PromotionEvaluationRequest(lines, List.of(promotion), List.of(), snapshots);
        assertThat(OrderedBuyPayPricing.tryPreview(multipleFree, quantityBenefits(multipleFree))).isNull();
        var multiplePromotions = new PromotionEvaluationRequest(lines,
                List.of(promotion(BuyXPayYMode.MIXED_TARGETS), promotion(BuyXPayYMode.SAME_PRODUCT)), List.of(), snapshots);
        assertThat(OrderedBuyPayPricing.tryPreview(multiplePromotions, quantityBenefits(multiplePromotions))).isNull();
    }

    @Test
    void aRateSpanningMultiplePriceBandsCanUseTheGeneralAllocation() {
        var lines = new ArrayList<PromotionEvaluationLine>();
        var snapshots = new HashMap<Integer, QuantityPriceSnapshot>();
        for (int index = 1; index <= 9; index++) {
            lines.add(line(index, UUID.randomUUID(), new BigDecimal("3"), new BigDecimal("2"), true, "IVA", "21"));
            snapshots.put(index, pack(3, "5", "2"));
        }
        lines.add(line(10, lines.getFirst().productId(), BigDecimal.ONE, new BigDecimal("1.99"), true, "IVA", "21"));
        snapshots.put(10, snapshots.get(1));
        var request = new PromotionEvaluationRequest(lines, List.of(promotion(BuyXPayYMode.MIXED_TARGETS)), List.of(), snapshots);

        assertThat(OrderedBuyPayPricing.tryPreview(request, quantityBenefits(request))).isNull();
    }

    @Test
    @Timeout(5)
    void thousandsOfDistinctQuantityRatesAndPricesCompeteWithTheSameBasketPromotion() {
        var lines = new ArrayList<PromotionEvaluationLine>();
        var snapshots = new HashMap<Integer, QuantityPriceSnapshot>();
        var expected = BigDecimal.ZERO;
        for (int index = 1; index <= 2400; index++) {
            var price = BigDecimal.valueOf(index + 100L, 2);
            var packSaving = index % 2 == 0 ? price.add(BigDecimal.ONE) : new BigDecimal("0.50");
            var packTotal = price.multiply(BigDecimal.valueOf(3)).subtract(packSaving);
            lines.add(line(index, UUID.randomUUID(), BigDecimal.valueOf(3), price, true, "IVA", "21"));
            snapshots.put(index, pack(3, packTotal.toPlainString(), price.toPlainString()));
            // Each remaining price has precisely three units, regardless of which rates are reserved.
            expected = expected.add(price.max(packSaving));
        }
        var preview = engine.preview(new PromotionEvaluationRequest(lines,
                List.of(promotion(BuyXPayYMode.MIXED_TARGETS)), List.of(), snapshots));
        assertThat(preview.payableDiscountTotal()).isEqualByComparingTo(expected);
        assertThat(preview.appliedPromotions().stream().filter(PromotionBenefit::quantityPrice).count()).isEqualTo(1200);
    }

    @Test
    void manyRateChoicesWithIncompletePacksMatchExhaustiveReservations() {
        int count = 12;
        var lines = new ArrayList<PromotionEvaluationLine>();
        var snapshots = new HashMap<Integer, QuantityPriceSnapshot>();
        for (int i = 0; i < count; i++) {
            int price = 100 + 11 * i, quantity = 1 + i % 4, saving = 15 + (47 * i) % 115;
            lines.add(line(i + 1, UUID.randomUUID(), BigDecimal.valueOf(quantity), BigDecimal.valueOf(price, 2), true, "IVA", "21"));
            snapshots.put(i + 1, pack(quantity, BigDecimal.valueOf(price * quantity - saving, 2).toPlainString(),
                    BigDecimal.valueOf(price, 2).toPlainString()));
        }
        long best = 0;
        for (int reserved = 0; reserved < 1 << count; reserved++) {
            int available = 0;
            long saving = 0;
            for (int i = count - 1; i >= 0; i--) {
                if ((reserved & (1 << i)) != 0) saving += 15 + (47 * i) % 115;
                else for (int j = 0; j < 1 + i % 4; j++) {
                    if (++available % 3 == 0) saving += 100 + 11 * i;
                }
            }
            best = Math.max(best, saving);
        }
        var preview = engine.preview(new PromotionEvaluationRequest(lines,
                List.of(promotion(BuyXPayYMode.MIXED_TARGETS)), List.of(), snapshots));
        assertThat(preview.payableDiscountTotal()).isEqualByComparingTo(BigDecimal.valueOf(best, 2));
    }

    @Test
    void disjointQuantityRatesAccumulateWithoutUsingTheCompetingRateBudget() {
        var product = UUID.randomUUID();
        var promotion = promotion(BuyXPayYMode.MIXED_TARGETS);
        var lines = new ArrayList<PromotionEvaluationLine>();
        lines.add(line(1, product, new BigDecimal("3"), new BigDecimal("3"), true, "IVA", "21"));
        var snapshots = new HashMap<Integer, QuantityPriceSnapshot>();
        for (int position = 2; position <= 13; position++) {
            lines.add(line(position, UUID.randomUUID(), new BigDecimal("3"), new BigDecimal("2"), true, "IVA", "21"));
            snapshots.put(position, pack(3, "5", "2"));
        }
        var request = new PromotionEvaluationRequest(lines, List.of(promotion),
                List.of(new PromotionTarget(promotion.id(), PromotionTargetType.PRODUCT, product)), snapshots);

        var fast = OrderedBuyPayPricing.tryPreview(request, quantityBenefits(request));

        assertThat(fast).isNotNull();
        assertThat(fast.payableDiscountTotal()).isEqualByComparingTo("15.00");
        assertThat(fast.appliedPromotions()).filteredOn(PromotionBenefit::quantityPrice).hasSize(12);
        assertThat(fast.appliedPromotions()).filteredOn(benefit -> !benefit.quantityPrice()).singleElement()
                .satisfies(benefit -> assertThat(benefit.affectedPositions()).containsExactly(1));
        assertThat(engine.preview(request)).isEqualTo(fast);
    }

    private List<PromotionBenefit> quantityBenefits(PromotionEvaluationRequest request) {
        // With no promotions, version 2 simply materializes each independent
        // quantity rate. No ordered or general mixed-basket solver is consulted.
        return engine.previewVersion2(new PromotionEvaluationRequest(request.lines(), List.of(),
                List.of(), request.quantityPrices())).appliedPromotions();
    }

    private static int oracle(int[] prices, BuyXPayYMode mode, int firstSaving, int secondSaving) {
        var values = new int[1 << prices.length];
        for (int units = 1; units < values.length; units++) {
            if (Integer.bitCount(units) != 3) continue;
            if (mode == BuyXPayYMode.SAME_PRODUCT && (units & 15) != 0 && (units & 240) != 0) continue;
            int minimum = Integer.MAX_VALUE;
            for (int index = 0; index < prices.length; index++) {
                if ((units & (1 << index)) != 0) minimum = Math.min(minimum, prices[index]);
            }
            values[units] = minimum;
        }
        values[5] = firstSaving;
        values[10] = secondSaving;
        var best = new int[values.length];
        for (int available = 1; available < best.length; available++) {
            for (int offer = available; offer > 0; offer = (offer - 1) & available) {
                if (values[offer] > 0) best[available] = Math.max(best[available], values[offer] + best[available ^ offer]);
            }
        }
        return best[best.length - 1];
    }

    private static Promotion promotion(BuyXPayYMode mode) {
        var result = Promotion.draft(UUID.randomUUID(), "3x2", PromotionType.BUY_X_PAY_Y, LocalDate.of(2026, 11, 23));
        result.configureBuyXPayY(new BigDecimal("3"), new BigDecimal("2"), mode);
        return result;
    }

    private static QuantityPriceSnapshot pack(int size, String total, String sale) {
        return new QuantityPriceSnapshot(new QuantityPricing(QuantityPricing.Mode.PACK,
                List.of(new QuantityPricing.Tier(size, new BigDecimal(total)))), new BigDecimal(sale));
    }

    private static PromotionEvaluationLine line(int position, UUID product, BigDecimal quantity,
            BigDecimal price, boolean included, String regime, String percent) {
        return new PromotionEvaluationLine(position, product, null, null, quantity, price,
                included, regime, new BigDecimal(percent), false, true);
    }
}

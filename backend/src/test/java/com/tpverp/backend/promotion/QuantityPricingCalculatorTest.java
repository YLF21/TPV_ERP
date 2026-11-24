package com.tpverp.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.catalog.QuantityPricing;
import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class QuantityPricingCalculatorTest {

    @Test
    void packsMixSizesAndPriceRemaindersAtListPrice() {
        var rule = packs(new QuantityPricing.Tier(3, decimal("5")),
                new QuantityPricing.Tier(5, decimal("8")));
        assertThat(total(rule, "2", "8")).isEqualByComparingTo("13.00");
        assertThat(total(rule, "2", "6")).isEqualByComparingTo("10.00");
        assertThat(total(rule, "2", "7")).isEqualByComparingTo("12.00");
        assertThat(total(rule, "2", "8.5")).isEqualByComparingTo("14.00");
        assertThat(QuantityPricingCalculator.calculate(rule, decimal("2"), decimal("8.5")).description())
                .contains("1 lote(s) de 3 uds a 5.00", "1 lote(s) de 5 uds a 8.00", "0.5 uds PVP");
    }

    @Test
    void packPricesNeverIncreaseTheListPrice() {
        assertThat(total(packs(new QuantityPricing.Tier(3, decimal("10"))), "2", "9"))
                .isEqualByComparingTo("18.00");
    }

    @Test
    void thresholdsAreInclusiveAndApplyToAllUnits() {
        var rule = new QuantityPricing(QuantityPricing.Mode.UNIT_THRESHOLD,
                List.of(new QuantityPricing.Tier(3, decimal("1.700")),
                        new QuantityPricing.Tier(6, decimal("1.500"))));
        assertThat(total(rule, "2", "2")).isEqualByComparingTo("4.00");
        assertThat(total(rule, "2", "3")).isEqualByComparingTo("5.10");
        assertThat(total(rule, "2", "5")).isEqualByComparingTo("8.50");
        assertThat(total(rule, "2", "6")).isEqualByComparingTo("9.00");
    }

    @Test
    void pricesRetainThirdDecimalUntilTheTotalIsRounded() {
        var rule = new QuantityPricing(QuantityPricing.Mode.UNIT_THRESHOLD,
                List.of(new QuantityPricing.Tier(3, decimal("0.333"))));
        assertThat(total(rule, "1", "3")).isEqualByComparingTo("1.00");
        assertThat(total(packs(new QuantityPricing.Tier(3, decimal("1"))), "1", "6"))
                .isEqualByComparingTo("2.00");
    }

    @Test
    @Timeout(2)
    void largeQuantitiesAreNotTruncatedOrExpandedIntoUnits() {
        var rule = packs(new QuantityPricing.Tier(3, decimal("5")),
                new QuantityPricing.Tier(5, decimal("8")));
        assertThat(total(rule, "2", "100000000003"))
                .isEqualByComparingTo("160000000005.00");
    }

    @Test
    @Timeout(2)
    void largeOddQuantitiesWithEqualPackRatesRetainTheUnavoidableListPriceRemainder() {
        var divisibleSizes = packs(new QuantityPricing.Tier(2, decimal("2")),
                new QuantityPricing.Tier(4, decimal("4")));
        var overlappingSizes = packs(new QuantityPricing.Tier(4, decimal("4")),
                new QuantityPricing.Tier(6, decimal("6")));
        assertThat(total(divisibleSizes, "2", "100000000001"))
                .isEqualByComparingTo("100000000002.00");
        assertThat(total(overlappingSizes, "2", "100000000001"))
                .isEqualByComparingTo("100000000002.00");
        assertThat(total(overlappingSizes, "2", "100000000007"))
                .isEqualByComparingTo("100000000008.00");
    }

    @Test
    void exactPackSearchAgreesWithIndependentExhaustiveDynamicProgramming() {
        var random = new Random(2081);
        for (int sample = 0; sample < 120; sample++) {
            var tiers = List.of(
                    new QuantityPricing.Tier(2, BigDecimal.valueOf(1 + random.nextInt(400), 2)),
                    new QuantityPricing.Tier(3, BigDecimal.valueOf(1 + random.nextInt(600), 2)),
                    new QuantityPricing.Tier(7, BigDecimal.valueOf(1 + random.nextInt(1400), 2)));
            var rule = new QuantityPricing(QuantityPricing.Mode.PACK, tiers);
            var expected = new BigDecimal[50];
            expected[0] = BigDecimal.ZERO;
            for (int quantity = 1; quantity < expected.length; quantity++) {
                var best = expected[quantity - 1].add(decimal("2"));
                for (var tier : tiers) {
                    if (quantity >= tier.quantity()) {
                        best = best.min(expected[quantity - tier.quantity()].add(tier.price()));
                    }
                }
                expected[quantity] = best;
                assertThat(total(rule, "2", Integer.toString(quantity)))
                        .as("sample %s, quantity %s", sample, quantity).isEqualByComparingTo(best);
            }
        }
    }

    private static QuantityPricing packs(QuantityPricing.Tier... tiers) {
        return new QuantityPricing(QuantityPricing.Mode.PACK, List.of(tiers));
    }

    private static BigDecimal total(QuantityPricing rule, String sale, String quantity) {
        return QuantityPricingCalculator.total(rule, decimal(sale), decimal(quantity));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}

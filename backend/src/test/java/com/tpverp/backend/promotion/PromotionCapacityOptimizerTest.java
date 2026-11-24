package com.tpverp.backend.promotion;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class PromotionCapacityOptimizerTest {
    @Test
    void agreesWithExhaustiveOracleForSmallOverlappingBoundedOptions() {
        Random random = new Random(328746128L);
        for (int scenario = 0; scenario < 1_000; scenario++) {
            int resources = 1 + random.nextInt(4);
            long[] capacity = new long[resources];
            for (int r = 0; r < resources; r++) capacity[r] = random.nextInt(8);
            List<PromotionCapacityOptimizer.Option> options = new ArrayList<>();
            for (int i = 0, count = 1 + random.nextInt(7); i < count; i++) {
                List<Integer> indices = new ArrayList<>();
                List<Long> quantities = new ArrayList<>();
                for (int r = 0; r < resources; r++) {
                    if (random.nextBoolean()) {
                        indices.add(r);
                        quantities.add(1L + random.nextInt(4));
                    }
                }
                if (indices.isEmpty()) {
                    indices.add(random.nextInt(resources));
                    quantities.add(1L + random.nextInt(4));
                }
                options.add(new PromotionCapacityOptimizer.Option(
                        indices.stream().mapToInt(Integer::intValue).toArray(),
                        quantities.stream().mapToLong(Long::longValue).toArray(),
                        random.nextInt(5), BigDecimal.valueOf(random.nextInt(100), random.nextInt(4))));
            }
            long[] actual = PromotionCapacityOptimizer.maximize(capacity, options);
            assertFeasible(capacity, options, actual);
            assertEquals(0, oracle(capacity.clone(), options, 0).compareTo(value(options, actual)),
                    "Oracle mismatch in scenario " + scenario);
        }
    }

    @Test
    @Timeout(5)
    void choosesExactRemainderCombinationAboveOneHundredThousandUnits() {
        var options = List.of(option(0, 3, Long.MAX_VALUE, "1.00"), option(0, 2, Long.MAX_VALUE, "0.67"));
        long[] actual = PromotionCapacityOptimizer.maximize(new long[] {100_001}, options);
        assertArrayEquals(new long[] {1, 49_999}, actual);
    }

    @Test
    @Timeout(5)
    void comparesOneTimeWholeResourceRulesAgainstRepeatedLots() {
        var options = List.of(
                option(0, 100_001, 1, "33500.32"),
                option(0, 3, Long.MAX_VALUE, "1.00"),
                option(0, 2, Long.MAX_VALUE, "0.67"));
        assertArrayEquals(new long[] {0, 1, 49_999},
                PromotionCapacityOptimizer.maximize(new long[] {100_001}, options));
    }

    @Test
    @Timeout(5)
    void handlesLongCapacityAndObjectiveWithoutOverflowOrFloatingPointLoss() {
        var options = List.of(
                option(0, 1, Long.MAX_VALUE, "9007199254740992.000000001"),
                option(0, 1, Long.MAX_VALUE, "9007199254740992.000000002"));
        assertArrayEquals(new long[] {0, Long.MAX_VALUE},
                PromotionCapacityOptimizer.maximize(new long[] {Long.MAX_VALUE}, options));
    }

    @Test
    void doesNotDiscardAUsefulBoundedDuplicate() {
        var options = List.of(option(0, 2, 1, "3"), option(0, 2, 10, "2"));
        assertArrayEquals(new long[] {1, 4}, PromotionCapacityOptimizer.maximize(new long[] {10}, options));
    }

    @Test
    void supportsUnsortedAndRepeatedResourceReferences() {
        var options = List.of(
                new PromotionCapacityOptimizer.Option(new int[] {1, 0, 1}, new long[] {1, 2, 2}, 10, new BigDecimal("5")),
                new PromotionCapacityOptimizer.Option(new int[] {0, 1}, new long[] {1, 1}, 10, new BigDecimal("2")));
        assertArrayEquals(new long[] {1, 2}, PromotionCapacityOptimizer.maximize(new long[] {4, 5}, options));
    }

    @Test
    void handlesResourceAndRepeatedConsumptionSumsBeyondLongMaxValue() {
        var options = List.of(
                new PromotionCapacityOptimizer.Option(new int[] {0, 0}, new long[] {Long.MAX_VALUE, 1}, 1, new BigDecimal("100")),
                new PromotionCapacityOptimizer.Option(new int[] {0, 1}, new long[] {Long.MAX_VALUE, Long.MAX_VALUE}, 1, new BigDecimal("10")),
                option(0, Long.MAX_VALUE, 1, "6"), option(1, Long.MAX_VALUE, 1, "6"));
        assertArrayEquals(new long[] {0, 0, 1, 1},
                PromotionCapacityOptimizer.maximize(new long[] {Long.MAX_VALUE, Long.MAX_VALUE}, options));
    }

    @Test
    @Timeout(5)
    void decomposesThousandsOfDisjointLargeCapacities() {
        long[] capacity = new long[2_000];
        List<PromotionCapacityOptimizer.Option> options = new ArrayList<>();
        for (int i = 0; i < capacity.length; i++) {
            capacity[i] = 100_000;
            options.add(option(i, 2, Long.MAX_VALUE, "1"));
            options.add(option(i, 3, Long.MAX_VALUE, "1"));
        }
        long[] actual = PromotionCapacityOptimizer.maximize(capacity, options);
        assertFeasible(capacity, options, actual);
        assertEquals(0, new BigDecimal("100000000").compareTo(value(options, actual)));
    }

    @Test
    void ignoresUnprofitableOptionsAndRetainsFreeBoundedBenefits() {
        var options = List.of(
                option(0, 1, 10, "-1"), option(0, 1, 10, "0"),
                new PromotionCapacityOptimizer.Option(new int[0], new long[0], 5, new BigDecimal("0.01")));
        assertArrayEquals(new long[] {0, 0, 5}, PromotionCapacityOptimizer.maximize(new long[] {10}, options));
    }

    @Test
    @Timeout(5)
    void optimizesOverlappingPacksAcrossMoreThanTwentyFourResources() {
        long[] capacity = new long[25];
        java.util.Arrays.fill(capacity, 1);
        List<PromotionCapacityOptimizer.Option> options = new ArrayList<>();
        for (int i = 0; i < capacity.length; i++) {
            options.add(new PromotionCapacityOptimizer.Option(new int[] {i, (i + 1) % capacity.length},
                    new long[] {1, 1}, 1, new BigDecimal("3")));
            options.add(option(i, 1, 1, "1"));
        }
        long[] actual = PromotionCapacityOptimizer.maximize(capacity, options);
        assertFeasible(capacity, options, actual);
        assertEquals(0, new BigDecimal("37").compareTo(value(options, actual)));
    }

    @Test
    @Timeout(5)
    void removesThousandsOfUnboundedDominatedIdenticalOptions() {
        List<PromotionCapacityOptimizer.Option> options = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) options.add(option(0, 2, Long.MAX_VALUE, "1"));
        options.add(option(0, 2, Long.MAX_VALUE, "1.01"));
        long[] actual = PromotionCapacityOptimizer.maximize(new long[] {100_000}, options);
        assertEquals(50_000, actual[5_000]);
        assertFeasible(new long[] {100_000}, options, actual);
    }

    private static PromotionCapacityOptimizer.Option option(int resource, long quantity, long limit, String saving) {
        return new PromotionCapacityOptimizer.Option(new int[] {resource}, new long[] {quantity}, limit, new BigDecimal(saving));
    }

    private static BigDecimal value(List<PromotionCapacityOptimizer.Option> options, long[] counts) {
        BigDecimal result = BigDecimal.ZERO;
        for (int i = 0; i < counts.length; i++) result = result.add(options.get(i).payableSaving().multiply(BigDecimal.valueOf(counts[i])));
        return result;
    }

    private static void assertFeasible(long[] capacity, List<PromotionCapacityOptimizer.Option> options, long[] counts) {
        assertEquals(options.size(), counts.length);
        BigInteger[] used = new BigInteger[capacity.length];
        java.util.Arrays.fill(used, BigInteger.ZERO);
        for (int i = 0; i < counts.length; i++) {
            var option = options.get(i);
            assertTrue(counts[i] >= 0 && counts[i] <= option.maxUses());
            for (int j = 0; j < option.resources().length; j++) {
                int r = option.resources()[j];
                used[r] = used[r].add(BigInteger.valueOf(option.quantities()[j]).multiply(BigInteger.valueOf(counts[i])));
            }
        }
        for (int r = 0; r < capacity.length; r++) assertTrue(used[r].compareTo(BigInteger.valueOf(capacity[r])) <= 0);
    }

    private static BigDecimal oracle(long[] remaining, List<PromotionCapacityOptimizer.Option> options, int index) {
        if (index == options.size()) return BigDecimal.ZERO;
        var option = options.get(index);
        long limit = option.maxUses();
        for (int j = 0; j < option.resources().length; j++)
            limit = Math.min(limit, remaining[option.resources()[j]] / option.quantities()[j]);
        BigDecimal best = BigDecimal.ZERO;
        for (long count = 0; count <= limit; count++) {
            for (int j = 0; j < option.resources().length; j++) remaining[option.resources()[j]] -= count * option.quantities()[j];
            BigDecimal candidate = option.payableSaving().multiply(BigDecimal.valueOf(count)).add(oracle(remaining, options, index + 1));
            best = best.max(candidate);
            for (int j = 0; j < option.resources().length; j++) remaining[option.resources()[j]] += count * option.quantities()[j];
        }
        return best;
    }
}

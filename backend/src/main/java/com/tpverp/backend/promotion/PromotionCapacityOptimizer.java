package com.tpverp.backend.promotion;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Exact bounded integer packing. Quantities are never expanded into individual units. */
final class PromotionCapacityOptimizer {
    private PromotionCapacityOptimizer() {}

    record Option(int[] resources, long[] quantities, long maxUses, BigDecimal payableSaving) {}

    static long[] maximize(long[] capacity, List<Option> options) {
        Objects.requireNonNull(capacity);
        Objects.requireNonNull(options);
        long[] result = new long[options.size()];
        int[] parent = new int[capacity.length];
        for (int r = 0; r < capacity.length; r++) {
            if (capacity[r] < 0) throw new IllegalArgumentException("Negative capacity");
            parent[r] = r;
        }
        List<Item> items = new ArrayList<>();
        int scale = 0;
        for (int i = 0; i < options.size(); i++) {
            Option option = Objects.requireNonNull(options.get(i));
            Objects.requireNonNull(option.resources());
            Objects.requireNonNull(option.quantities());
            Objects.requireNonNull(option.payableSaving());
            if (option.resources().length != option.quantities().length || option.maxUses() < 0)
                throw new IllegalArgumentException("Invalid option");
            // Canonicalize resource order, including repeated references, without long overflow.
            Map<Integer, BigInteger> amounts = new TreeMap<>();
            for (int j = 0; j < option.resources().length; j++) {
                int r = option.resources()[j];
                long q = option.quantities()[j];
                if (r < 0 || r >= capacity.length || q <= 0)
                    throw new IllegalArgumentException("Invalid resource consumption");
                amounts.merge(r, BigInteger.valueOf(q), BigInteger::add);
            }
            if (option.maxUses() == 0 || option.payableSaving().signum() <= 0) continue;
            if (amounts.isEmpty()) {
                result[i] = option.maxUses();
                continue;
            }
            long limit = option.maxUses();
            for (var entry : amounts.entrySet()) {
                limit = Math.min(limit, BigInteger.valueOf(capacity[entry.getKey()])
                        .divide(entry.getValue()).longValueExact());
            }
            if (limit == 0) continue;
            int[] resources = amounts.keySet().stream().mapToInt(Integer::intValue).toArray();
            long[] quantities = amounts.values().stream().mapToLong(BigInteger::longValueExact).toArray();
            Item item = new Item(i, resources, quantities, limit, option.payableSaving());
            items.add(item);
            scale = Math.max(scale, option.payableSaving().scale());
            for (int j = 1; j < resources.length; j++) union(parent, resources[0], resources[j]);
        }
        Map<Integer, List<Item>> components = new LinkedHashMap<>();
        for (Item item : items) {
            item.value = item.saving.setScale(scale).unscaledValue();
            components.computeIfAbsent(find(parent, item.resources[0]), ignored -> new ArrayList<>()).add(item);
        }
        for (List<Item> component : components.values()) new Search(capacity, component, result).solve();
        return result;
    }

    private static int find(int[] parent, int r) {
        while (parent[r] != r) {
            parent[r] = parent[parent[r]];
            r = parent[r];
        }
        return r;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, b)] = find(parent, a);
    }

    private static final class Item {
        final int original;
        final int[] resources;
        final long[] quantities;
        final long limit;
        final BigDecimal saving;
        BigInteger value;

        Item(int original, int[] resources, long[] quantities, long limit, BigDecimal saving) {
            this.original = original;
            this.resources = resources;
            this.quantities = quantities;
            this.limit = limit;
            this.saving = saving;
        }
    }

    private static final class Search {
        private final Item[] items;
        private final long[] output;
        private final long[] remaining;
        private final long[] lower;
        private final long[] upper;
        private final long[] limits;
        private final long[] best;
        private final long[] relaxed;
        private final long[] scratch;
        private final long[] greedyRemaining;
        private final int[][] orders;
        private final BigInteger[][] weights;
        private final BigInteger[][] resourcePrices;
        private final int[] componentResources;
        private BigInteger committed = BigInteger.ZERO;
        private BigInteger incumbent = BigInteger.ZERO;

        Search(long[] capacity, List<Item> component, long[] output) {
            this.output = output;
            // An identical option with a nonbinding cap can replace any worse identical option.
            Map<String, Item> unrestricted = new LinkedHashMap<>();
            for (Item item : component) {
                long physicalLimit = Long.MAX_VALUE;
                for (int j = 0; j < item.resources.length; j++)
                    physicalLimit = Math.min(physicalLimit, capacity[item.resources[j]] / item.quantities[j]);
                if (item.limit == physicalLimit) {
                    String key = Arrays.toString(item.resources) + Arrays.toString(item.quantities);
                    Item previous = unrestricted.get(key);
                    if (previous == null || item.value.compareTo(previous.value) > 0) unrestricted.put(key, item);
                }
            }
            items = component.stream().filter(item -> {
                Item replacement = unrestricted.get(Arrays.toString(item.resources) + Arrays.toString(item.quantities));
                return replacement == null || replacement == item || replacement.value.compareTo(item.value) < 0;
            }).toArray(Item[]::new);
            remaining = capacity.clone();
            componentResources = Arrays.stream(items).flatMapToInt(item -> Arrays.stream(item.resources)).distinct().toArray();
            lower = new long[items.length];
            upper = Arrays.stream(items).mapToLong(item -> item.limit).toArray();
            limits = new long[items.length];
            best = new long[items.length];
            relaxed = new long[items.length];
            scratch = new long[items.length];
            greedyRemaining = capacity.clone();

            // Each row is a valid relaxation. The final row sums all resource constraints.
            // Limit individual rows with many resources: weaker bounds affect speed, never exactness.
            int individualRows = componentResources.length <= 16 ? componentResources.length : 0;
            BigInteger[] surrogate = surrogatePrices(capacity);
            int surrogateRows = surrogate == null ? 0 : 1;
            resourcePrices = new BigInteger[individualRows + surrogateRows + 1][componentResources.length];
            weights = new BigInteger[resourcePrices.length][items.length];
            orders = new int[weights.length][];
            for (int row = 0; row < weights.length; row++) {
                for (int r = 0; r < componentResources.length; r++) {
                    resourcePrices[row][r] = row == weights.length - 1 ? BigInteger.ONE
                            : row == individualRows ? surrogate[r]
                            : row == r ? BigInteger.ONE : BigInteger.ZERO;
                }
                for (int i = 0; i < items.length; i++) {
                    BigInteger weight = BigInteger.ZERO;
                    for (int j = 0; j < items[i].resources.length; j++) {
                        for (int r = 0; r < componentResources.length; r++) {
                            if (items[i].resources[j] == componentResources[r])
                                weight = weight.add(BigInteger.valueOf(items[i].quantities[j]).multiply(resourcePrices[row][r]));
                        }
                    }
                    weights[row][i] = weight;
                }
                final int currentRow = row;
                Integer[] indices = new Integer[items.length];
                for (int i = 0; i < items.length; i++) indices[i] = i;
                Arrays.sort(indices, (a, b) -> {
                    int compare = items[b].value.multiply(weights[currentRow][a])
                            .compareTo(items[a].value.multiply(weights[currentRow][b]));
                    return compare == 0 ? Integer.compare(a, b) : compare;
                });
                orders[row] = Arrays.stream(indices).mapToInt(Integer::intValue).toArray();
            }
        }

        /**
         * Suggests a useful nonnegative combination of resource constraints. Floating point
         * is used ONLY to choose weights, never for objective comparisons or pruning. Even
         * an unfinished/ill-conditioned simplex produces a valid relaxation once its weights
         * are converted to nonnegative integers and all bounds are recomputed exactly.
         */
        private BigInteger[] surrogatePrices(long[] capacity) {
            int m = componentResources.length;
            int n = items.length;
            if (m < 2 || m > 32 || n < 2) return null;
            int rhs = n + m;
            double[][] tableau = new double[m + 1][rhs + 1];
            long[] rowScale = new long[m];
            Arrays.fill(rowScale, 1L);
            BigInteger maxValue = BigInteger.ONE;
            for (Item item : items) {
                maxValue = maxValue.max(item.value);
                for (int j = 0; j < item.resources.length; j++) {
                    for (int r = 0; r < m; r++) {
                        if (item.resources[j] == componentResources[r]) rowScale[r] = Math.max(rowScale[r], item.quantities[j]);
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                tableau[m][i] = -new BigDecimal(items[i].value)
                        .divide(new BigDecimal(maxValue), MathContext.DECIMAL64).doubleValue();
                for (int j = 0; j < items[i].resources.length; j++) {
                    for (int r = 0; r < m; r++) {
                        if (items[i].resources[j] == componentResources[r])
                            tableau[r][i] = (double) items[i].quantities[j] / rowScale[r];
                    }
                }
            }
            for (int r = 0; r < m; r++) {
                tableau[r][n + r] = 1;
                tableau[r][rhs] = (double) capacity[componentResources[r]] / rowScale[r];
            }
            // This is a performance hint; a finite pivot budget cannot exclude a solution.
            for (int pivot = 0; pivot < 1_000; pivot++) {
                int entering = -1;
                for (int col = 0; col < rhs; col++) {
                    if (tableau[m][col] < -1e-10 && (entering < 0 || tableau[m][col] < tableau[m][entering])) entering = col;
                }
                if (entering < 0) break;
                int leaving = -1;
                double ratio = Double.POSITIVE_INFINITY;
                for (int r = 0; r < m; r++) {
                    if (tableau[r][entering] > 1e-12) {
                        double candidate = tableau[r][rhs] / tableau[r][entering];
                        if (candidate < ratio) {
                            ratio = candidate;
                            leaving = r;
                        }
                    }
                }
                if (leaving < 0 || !Double.isFinite(ratio)) break;
                double divisor = tableau[leaving][entering];
                for (int col = 0; col <= rhs; col++) tableau[leaving][col] /= divisor;
                for (int r = 0; r <= m; r++) {
                    if (r == leaving) continue;
                    double factor = tableau[r][entering];
                    if (factor == 0) continue;
                    for (int col = 0; col <= rhs; col++) tableau[r][col] -= factor * tableau[leaving][col];
                }
            }
            BigInteger[] prices = new BigInteger[m];
            boolean positive = false;
            for (int r = 0; r < m; r++) {
                double price = tableau[m][n + r];
                prices[r] = !Double.isFinite(price) || price <= 0 ? BigInteger.ZERO
                        : BigDecimal.valueOf(price).scaleByPowerOfTen(18)
                                .divide(BigDecimal.valueOf(rowScale[r]), 0, RoundingMode.CEILING).toBigIntegerExact();
                positive |= prices[r].signum() > 0;
            }
            return positive ? prices : null;
        }

        void solve() {
            if (items.length == 1) {
                output[items[0].original] = upper[0];
                return;
            }
            if (solveSmallStateSpace()) return;
            ArrayDeque<Branch> stack = new ArrayDeque<>();
            boolean visit = true;
            while (true) {
                if (visit) {
                    int variable = evaluate();
                    if (variable >= 0) {
                        // Bisect multiplicity intervals: depth is logarithmic in quantities,
                        // and the explicit stack also handles thousands of option variables.
                        long oldLow = lower[variable];
                        long oldHigh = upper[variable];
                        long high = oldLow + limits[variable];
                        long middle = oldLow + (high - oldLow) / 2;
                        Branch branch = new Branch(variable, oldLow, oldHigh, middle);
                        stack.push(branch);
                        // Explore the half containing the best relaxation first.
                        branch.highFirst = relaxed[variable] > middle - oldLow;
                        apply(branch, branch.highFirst);
                        continue;
                    }
                }
                visit = false;
                while (!stack.isEmpty()) {
                    Branch branch = stack.peek();
                    restore(branch);
                    if (!branch.second) {
                        branch.second = true;
                        apply(branch, !branch.highFirst);
                        visit = true;
                        break;
                    }
                    stack.pop();
                }
                if (!visit) break;
            }
            for (int i = 0; i < items.length; i++) output[items[i].original] = best[i];
        }

        /** Small mixed-radix DP is especially effective for many overlapping one-unit packs. */
        private boolean solveSmallStateSpace() {
            int states = 1;
            int[] strides = new int[remaining.length];
            for (int resource : componentResources) {
                long capacity = remaining[resource];
                if (capacity > 32 || states > 262_144 / (capacity + 1)) return false;
                strides[resource] = states;
                states *= (int) (capacity + 1);
            }
            if ((long) states * items.length > 25_000_000) return false;
            int[] deltas = new int[items.length];
            for (int i = 0; i < items.length; i++) {
                // Resource state alone is insufficient for an additionally binding use cap.
                if (feasible(i, Long.MAX_VALUE, remaining) != upper[i]) return false;
                for (int j = 0; j < items[i].resources.length; j++)
                    deltas[i] += (int) items[i].quantities[j] * strides[items[i].resources[j]];
            }
            BigInteger[] values = new BigInteger[states];
            int[] choice = new int[states];
            Arrays.fill(choice, -1);
            for (int state = 0; state < states; state++) {
                for (int resource : componentResources)
                    greedyRemaining[resource] = (state / strides[resource]) % (remaining[resource] + 1);
                BigInteger value = BigInteger.ZERO;
                for (int i = 0; i < items.length; i++) {
                    if (feasible(i, 1, greedyRemaining) == 0) continue;
                    BigInteger candidate = values[state - deltas[i]].add(items[i].value);
                    if (candidate.compareTo(value) > 0) {
                        value = candidate;
                        choice[state] = i;
                    }
                }
                values[state] = value;
            }
            int state = states - 1;
            while (choice[state] >= 0) {
                int i = choice[state];
                output[items[i].original]++;
                state -= deltas[i];
            }
            return true;
        }

        /** Returns a variable to split, or -1 when this entire interval box is solved/pruned. */
        private int evaluate() {
            for (int i = 0; i < items.length; i++) limits[i] = feasible(i, upper[i] - lower[i], remaining);
            BigInteger bound = null;
            int fraction = -1;
            for (int row = 0; row < orders.length; row++) {
                BigInteger available = BigInteger.ZERO;
                for (int r = 0; r < componentResources.length; r++)
                    available = available.add(BigInteger.valueOf(remaining[componentResources[r]]).multiply(resourcePrices[row][r]));
                BigInteger value = committed;
                int currentFraction = -1;
                Arrays.fill(scratch, 0);
                for (int i : orders[row]) {
                    if (limits[i] == 0) continue;
                    BigInteger weight = weights[row][i];
                    if (weight.signum() == 0) {
                        scratch[i] = limits[i];
                        value = value.add(items[i].value.multiply(BigInteger.valueOf(limits[i])));
                        continue;
                    }
                    BigInteger total = weight.multiply(BigInteger.valueOf(limits[i]));
                    if (total.compareTo(available) <= 0) {
                        scratch[i] = limits[i];
                        available = available.subtract(total);
                        value = value.add(items[i].value.multiply(BigInteger.valueOf(limits[i])));
                    } else {
                        scratch[i] = available.divide(weight).longValueExact();
                        // All objective values are integers in a common decimal scale, so
                        // flooring this rational relaxation is an admissible upper bound.
                        value = value.add(items[i].value.multiply(available).divide(weight));
                        if (available.remainder(weight).signum() != 0) currentFraction = i;
                        break;
                    }
                }
                if (bound == null || value.compareTo(bound) < 0) {
                    bound = value;
                    fraction = currentFraction;
                    System.arraycopy(scratch, 0, relaxed, 0, scratch.length);
                }
                if (bound.compareTo(incumbent) <= 0) return -1;
            }
            // A feasible completion only improves the incumbent; it never determines a cut.
            greedy(orders[orders.length - 1], true);
            if (bound.compareTo(incumbent) <= 0) return -1;
            if (relaxationIsFeasible()) {
                BigInteger value = committed;
                for (int i = 0; i < items.length; i++) value = value.add(items[i].value.multiply(BigInteger.valueOf(relaxed[i])));
                save(value, relaxed);
                if (bound.compareTo(incumbent) <= 0) return -1;
            }
            if (fraction >= 0 && limits[fraction] > 0) return fraction;
            // If an integral relaxation violates another resource, split one of its selected
            // variables. Otherwise any unfixed variable completes the exact partition.
            for (int i : orders[orders.length - 1]) if (limits[i] > 0 && relaxed[i] > 0) return i;
            for (int i = 0; i < items.length; i++) if (limits[i] > 0) return i;
            return -1;
        }

        private boolean relaxationIsFeasible() {
            System.arraycopy(remaining, 0, greedyRemaining, 0, remaining.length);
            for (int i = 0; i < items.length; i++) {
                if (feasible(i, relaxed[i], greedyRemaining) < relaxed[i]) return false;
                consume(i, relaxed[i], greedyRemaining);
            }
            return true;
        }

        private void greedy(int[] order, boolean useRelaxation) {
            System.arraycopy(remaining, 0, greedyRemaining, 0, remaining.length);
            Arrays.fill(scratch, 0);
            BigInteger value = committed;
            if (useRelaxation) {
                for (int i : order) {
                    long uses = feasible(i, relaxed[i], greedyRemaining);
                    scratch[i] = uses;
                    consume(i, uses, greedyRemaining);
                    value = value.add(items[i].value.multiply(BigInteger.valueOf(uses)));
                }
            }
            for (int i : order) {
                long uses = feasible(i, limits[i] - scratch[i], greedyRemaining);
                scratch[i] += uses;
                consume(i, uses, greedyRemaining);
                value = value.add(items[i].value.multiply(BigInteger.valueOf(uses)));
            }
            save(value, scratch);
        }

        private void save(BigInteger value, long[] extra) {
            if (value.compareTo(incumbent) > 0) {
                incumbent = value;
                for (int i = 0; i < items.length; i++) best[i] = lower[i] + extra[i];
            }
        }

        private long feasible(int i, long limit, long[] capacity) {
            Item item = items[i];
            for (int j = 0; j < item.resources.length; j++)
                limit = Math.min(limit, capacity[item.resources[j]] / item.quantities[j]);
            return limit;
        }

        private void consume(int i, long count, long[] capacity) {
            Item item = items[i];
            for (int j = 0; j < item.resources.length; j++) capacity[item.resources[j]] -= item.quantities[j] * count;
        }

        private void apply(Branch branch, boolean high) {
            int i = branch.variable;
            if (high) {
                long increment = branch.middle + 1 - lower[i];
                lower[i] += increment;
                consume(i, increment, remaining);
                committed = committed.add(items[i].value.multiply(BigInteger.valueOf(increment)));
            } else upper[i] = branch.middle;
        }

        private void restore(Branch branch) {
            int i = branch.variable;
            long increment = lower[i] - branch.low;
            consume(i, -increment, remaining);
            committed = committed.subtract(items[i].value.multiply(BigInteger.valueOf(increment)));
            lower[i] = branch.low;
            upper[i] = branch.high;
        }
    }

    private static final class Branch {
        final int variable;
        final long low;
        final long high;
        final long middle;
        boolean highFirst;
        boolean second;

        Branch(int variable, long low, long high, long middle) {
            this.variable = variable;
            this.low = low;
            this.high = high;
            this.middle = middle;
        }
    }
}

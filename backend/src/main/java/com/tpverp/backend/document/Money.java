package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private Money() {
    }

    // Normaliza cualquier importe al formato monetario EUR del sistema.
    public static BigDecimal euros(String value) {
        return euros(new BigDecimal(value));
    }

    // Normaliza cualquier importe al formato monetario EUR del sistema.
    public static BigDecimal euros(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("importe es obligatorio");
        }
        return value.setScale(SCALE, ROUNDING);
    }

    // Calculates a percentage and rounds the result as a money amount.
    public static BigDecimal percentage(BigDecimal amount, BigDecimal percentage) {
        return euros(euros(amount).multiply(validPercentage(percentage)).divide(HUNDRED));
    }

    static BigDecimal validPercentage(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("porcentaje debe estar entre 0 y 100");
        }
        return value.setScale(SCALE, ROUNDING);
    }

    /**
     * Distributes a monetary target proportionally across ordered non-negative
     * weights. Any residual cents are assigned by largest remainder and, on a
     * tie, by the original order supplied by the caller.
     */
    static List<BigDecimal> allocateByLargestRemainder(
            BigDecimal target,
            List<BigDecimal> weights) {
        var normalizedTarget = euros(target);
        if (normalizedTarget.signum() < 0) {
            throw new IllegalArgumentException("target no puede ser negativo");
        }
        var normalizedWeights = List.copyOf(weights == null ? List.of() : weights)
                .stream()
                .map(weight -> {
                    if (weight == null || weight.signum() < 0) {
                        throw new IllegalArgumentException(
                                "los pesos no pueden ser nulos ni negativos");
                    }
                    return weight;
                })
                .toList();
        var totalWeight = normalizedWeights.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (normalizedWeights.isEmpty() || totalWeight.signum() == 0) {
            if (normalizedTarget.signum() != 0) {
                throw new IllegalArgumentException(
                        "no se puede distribuir un importe sin pesos positivos");
            }
            return normalizedWeights.stream()
                    .map(ignored -> BigDecimal.ZERO.setScale(SCALE))
                    .toList();
        }
        if (normalizedTarget.compareTo(totalWeight) > 0) {
            throw new IllegalArgumentException(
                    "el importe distribuido no puede superar los pesos");
        }

        var raw = normalizedWeights.stream()
                .map(weight -> normalizedTarget.multiply(weight)
                        .divide(totalWeight, SCALE + 12, RoundingMode.DOWN))
                .toList();
        var allocated = raw.stream()
                .map(value -> value.setScale(SCALE, RoundingMode.DOWN))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        var allocatedTotal = allocated.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var remainingCents = normalizedTarget.subtract(allocatedTotal)
                .movePointRight(SCALE)
                .intValueExact();
        var order = IntStream.range(0, raw.size()).boxed()
                .sorted(Comparator.<Integer, BigDecimal>comparing(
                                index -> raw.get(index).subtract(allocated.get(index)))
                        .reversed()
                        .thenComparingInt(Integer::intValue))
                .toList();
        var cent = BigDecimal.ONE.movePointLeft(SCALE);
        for (int index = 0; index < remainingCents; index++) {
            var allocationIndex = order.get(index);
            allocated.set(allocationIndex,
                    allocated.get(allocationIndex).add(cent));
        }
        return List.copyOf(allocated);
    }
}

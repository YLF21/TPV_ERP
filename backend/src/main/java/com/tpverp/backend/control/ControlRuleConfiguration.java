package com.tpverp.backend.control;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class ControlRuleConfiguration {

    static final String THRESHOLD_PERCENT = "thresholdPercent";
    static final String MINIMUM_COUNT = "minimumCount";

    private ControlRuleConfiguration() {
    }

    static Map<String, Object> normalize(ControlAlertType type, Map<String, Object> value) {
        Objects.requireNonNull(type, "type");
        var config = value == null ? Map.<String, Object>of() : value;
        if (type.parameterKind() == ControlRuleParameterKind.NONE) {
            if (!config.isEmpty()) {
                throw new IllegalArgumentException("Este tipo de regla no admite configuracion");
            }
            return Map.of();
        }
        if (type.parameterKind() == ControlRuleParameterKind.QUANTITY) {
            return normalizeQuantity(config);
        }
        return normalizePercentage(config);
    }

    private static Map<String, Object> normalizePercentage(Map<String, Object> config) {
        if (!config.keySet().equals(java.util.Set.of(THRESHOLD_PERCENT))) {
            throw new IllegalArgumentException("thresholdPercent es la unica configuracion permitida");
        }
        Object raw = config.get(THRESHOLD_PERCENT);
        if (raw == null) {
            throw new IllegalArgumentException("thresholdPercent es obligatorio");
        }
        final BigDecimal threshold;
        try {
            threshold = new BigDecimal(raw.toString()).stripTrailingZeros();
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("thresholdPercent debe ser numerico", exception);
        }
        if (threshold.signum() < 0 || threshold.compareTo(new BigDecimal("100")) > 0
                || threshold.scale() > 2) {
            throw new IllegalArgumentException("thresholdPercent debe estar entre 0 y 100 con dos decimales como maximo");
        }
        var normalized = new LinkedHashMap<String, Object>();
        normalized.put(THRESHOLD_PERCENT, threshold);
        return Map.copyOf(normalized);
    }

    private static Map<String, Object> normalizeQuantity(Map<String, Object> config) {
        if (!config.keySet().equals(java.util.Set.of(MINIMUM_COUNT))) {
            throw new IllegalArgumentException("minimumCount es la unica configuracion permitida");
        }
        Object raw = config.get(MINIMUM_COUNT);
        if (raw == null) {
            throw new IllegalArgumentException("minimumCount es obligatorio");
        }
        final int minimumCount;
        try {
            var decimal = new BigDecimal(raw.toString()).stripTrailingZeros();
            if (decimal.scale() > 0) {
                throw new IllegalArgumentException("minimumCount debe ser un numero entero");
            }
            minimumCount = decimal.intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("minimumCount debe ser un numero entero", exception);
        }
        if (minimumCount < 2 || minimumCount > 999) {
            throw new IllegalArgumentException("minimumCount debe estar entre 2 y 999");
        }
        return Map.of(MINIMUM_COUNT, minimumCount);
    }

    static BigDecimal threshold(Map<String, Object> config) {
        return new BigDecimal(config.get(THRESHOLD_PERCENT).toString());
    }

    static int minimumCount(Map<String, Object> config) {
        return new BigDecimal(config.get(MINIMUM_COUNT).toString()).intValueExact();
    }
}

package com.tpverp.backend.party;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

final class PartyValues {

    private PartyValues() {
    }

    static String required(String value, String field) {
        String normalized = optional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return normalized;
    }

    static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String document(String value) {
        return required(value, "numeroDocumento").toUpperCase(Locale.ROOT);
    }

    static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal discount(BigDecimal value) {
        BigDecimal discount = money(value);
        if (discount.signum() < 0 || discount.compareTo(new BigDecimal("100.00")) > 0) {
            throw new IllegalArgumentException("descuento debe estar entre 0 y 100");
        }
        return discount;
    }
}

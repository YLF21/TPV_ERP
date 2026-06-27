package com.tpverp.backend.party;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import com.tpverp.backend.shared.domain.TextValues;

final class PartyValues {

    private PartyValues() {
    }

    static String required(String value, String field) {
        return TextValues.required(value, field);
    }

    static String optional(String value) {
        return TextValues.optional(value);
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

package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.math.RoundingMode;

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

    // Calcula un porcentaje y redondea el resultado como importe monetario.
    public static BigDecimal percentage(BigDecimal amount, BigDecimal percentage) {
        return euros(euros(amount).multiply(validPercentage(percentage)).divide(HUNDRED));
    }

    static BigDecimal validPercentage(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("porcentaje debe estar entre 0 y 100");
        }
        return value.setScale(SCALE, ROUNDING);
    }
}

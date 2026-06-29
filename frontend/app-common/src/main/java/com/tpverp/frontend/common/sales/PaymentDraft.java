package com.tpverp.frontend.common.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record PaymentDraft(BigDecimal total, BigDecimal received) {

    public PaymentDraft {
        total = money(total);
        received = money(received);
    }

    public BigDecimal remaining() {
        BigDecimal remaining = total.subtract(received);
        return remaining.signum() < 0 ? BigDecimal.ZERO.setScale(2) : money(remaining);
    }

    public BigDecimal difference() {
        return money(received.subtract(total));
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}

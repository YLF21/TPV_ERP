package com.tpverp.backend.cash;

import com.tpverp.backend.document.Money;
import java.math.BigDecimal;
import java.util.List;

public final class CashDenomination {

    private static final List<BigDecimal> EURO_ORDER = List.of(
            Money.euros("100"),
            Money.euros("50"),
            Money.euros("20"),
            Money.euros("10"),
            Money.euros("5"),
            Money.euros("2"),
            Money.euros("1"),
            Money.euros("0.50"),
            Money.euros("0.20"),
            Money.euros("0.10"),
            Money.euros("0.05"),
            Money.euros("0.02"),
            Money.euros("0.01"));

    private CashDenomination() {
    }

    // Devuelve las denominaciones EUR en el orden operativo de arqueo.
    public static List<BigDecimal> valuesInEuroOrder() {
        return EURO_ORDER;
    }
}

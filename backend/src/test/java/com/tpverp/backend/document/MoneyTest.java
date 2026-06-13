package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void roundsHalfUpToTwoDecimals() {
        assertThat(Money.euros("10.125")).isEqualByComparingTo("10.13");
        assertThat(Money.euros("10.124")).isEqualByComparingTo("10.12");
    }

    @Test
    void calculatesPercentageWithMonetaryRounding() {
        assertThat(Money.percentage(Money.euros("10.05"), new BigDecimal("5")))
                .isEqualByComparingTo("0.50");
    }
}

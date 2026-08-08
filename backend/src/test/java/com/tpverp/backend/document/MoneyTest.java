package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
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

    @Test
    void allocatesResidualCentsByLargestRemainderAndStableInputOrder() {
        assertThat(Money.allocateByLargestRemainder(
                new BigDecimal("0.02"),
                List.of(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)))
                .containsExactly(
                        new BigDecimal("0.01"),
                        new BigDecimal("0.01"),
                        new BigDecimal("0.00"));

        assertThat(Money.allocateByLargestRemainder(
                new BigDecimal("90.00"),
                List.of(new BigDecimal("100.00"))))
                .containsExactly(new BigDecimal("90.00"));
    }
}

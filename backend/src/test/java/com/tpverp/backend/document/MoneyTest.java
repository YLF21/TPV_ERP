package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void preservesUnitPrecisionAndRoundsOnlyTheExtendedAmount() {
        assertThat(Money.exactUnitPrice(new BigDecimal("2.208"))).isEqualByComparingTo("2.208");
        assertThat(Money.euros(Money.exactUnitPrice(new BigDecimal("2.208")).multiply(BigDecimal.TEN)))
                .isEqualByComparingTo("22.08");
        assertThat(Money.euros(Money.exactUnitPrice(new BigDecimal("1.235")).multiply(new BigDecimal("3"))))
                .isEqualByComparingTo("3.71");
        assertThat(Money.unitPrice(new BigDecimal("10.000"))).isEqualTo(new BigDecimal("10.00"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Money.exactUnitPrice(new BigDecimal("1.2345")))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Money.exactUnitPrice(new BigDecimal("100000000000000000")))
                .isInstanceOf(IllegalArgumentException.class);
    }

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

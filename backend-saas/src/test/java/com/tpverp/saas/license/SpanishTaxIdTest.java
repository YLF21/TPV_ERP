package com.tpverp.saas.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SpanishTaxIdTest {

    @Test
    void normalizesSpanishTaxIds() {
        assertThat(SpanishTaxId.normalize(" b-12345678 ")).isEqualTo("B12345678");
    }

    @ParameterizedTest
    @CsvSource({
            "12345678Z,12345678Z",
            "X1234567L,X1234567L",
            "K1234567L,K1234567L",
            "B12345674,B12345674"
    })
    void validatesAndNormalizesSpanishTaxIds(String input, String expected) {
        assertThat(SpanishTaxId.validate(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "12345678A",
            "X1234567A",
            "K1234567A",
            "B12345678",
            "DEMO-00000000"
    })
    void rejectsInvalidTaxIds(String input) {
        assertThatThrownBy(() -> SpanishTaxId.validate(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NIF");
    }
}

package com.tpverp.backend.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SpanishTaxIdTest {

    @Test
    void normalizesSpanishTaxIds() {
        assertThat(SpanishTaxId.normalize(" b-12345678 ")).isEqualTo("B12345678");
    }

    @Test
    void rejectsStructurallyInvalidTaxIds() {
        assertThatThrownBy(() -> SpanishTaxId.normalize("DEMO-00000000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NIF");
    }
}

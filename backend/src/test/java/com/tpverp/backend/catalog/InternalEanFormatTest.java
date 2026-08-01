package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InternalEanFormatTest {

    @Test
    void composesRestrictedEan13FromCompanyStoreAndSequence() {
        var code = InternalEanFormat.EAN_13.compose("34", "007", 42);

        assertThat(code).hasSize(13).startsWith("234007000042");
        assertThat(InternalEanFormat.validate(code).valid()).isTrue();
    }

    @Test
    void composesRestrictedEan8FromStoreAndSequence() {
        var code = InternalEanFormat.EAN_8.compose("34", "007", 42);

        assertThat(code).hasSize(8).startsWith("2007042");
        assertThat(InternalEanFormat.validate(code).valid()).isTrue();
    }

    @Test
    void identifiesInvalidLengthCharactersAndCheckDigit() {
        assertThat(InternalEanFormat.validate("123").reason())
                .isEqualTo("INVALID_LENGTH");
        assertThat(InternalEanFormat.validate("1234567A").reason())
                .isEqualTo("NON_NUMERIC");
        assertThat(InternalEanFormat.validate("20070420").reason())
                .isEqualTo("INVALID_CHECK_DIGIT");
    }

    @Test
    void refusesSequenceOutsideFormatCapacity() {
        assertThatThrownBy(() ->
                InternalEanFormat.EAN_8.compose("34", "007", 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("internal_ean_sequence_exhausted");
    }
}

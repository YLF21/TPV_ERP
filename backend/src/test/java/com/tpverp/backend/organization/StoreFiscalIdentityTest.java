package com.tpverp.backend.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StoreFiscalIdentityTest {

    @ParameterizedTest
    @ValueSource(strings = {"001", "999"})
    void acceptsThreeDigitFiscalCodes(String code) {
        assertThat(StoreFiscalIdentity.code(code)).isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"000", "01", "1000", "ABC", " "})
    void rejectsInvalidFiscalCodes(String code) {
        assertThatThrownBy(() -> StoreFiscalIdentity.code(code))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Atlantic/Canary", "Europe/Madrid"})
    void acceptsSupportedTimezones(String timezone) {
        assertThat(StoreFiscalIdentity.timezone(timezone)).isEqualTo(timezone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Europe/London", "UTC", " "})
    void rejectsUnsupportedTimezones(String timezone) {
        assertThatThrownBy(() -> StoreFiscalIdentity.timezone(timezone))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

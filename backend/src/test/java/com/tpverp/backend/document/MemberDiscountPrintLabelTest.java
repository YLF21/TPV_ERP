package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MemberDiscountPrintLabelTest {

    @Test
    void formatsWholeAndDecimalPersistedPercentagesWithoutTechnicalZeros() {
        assertThat(MemberDiscountPrintLabel.format(new BigDecimal("5.00")))
                .isEqualTo("Descuento miembro 5%");
        assertThat(MemberDiscountPrintLabel.format(new BigDecimal("7.50")))
                .isEqualTo("Descuento miembro 7.5%");
    }
}

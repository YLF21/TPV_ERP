package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExcelColumnTest {

    @Test
    void convertsLettersToZeroBasedIndex() {
        assertThat(ExcelColumn.index("A")).isZero();
        assertThat(ExcelColumn.index("B")).isEqualTo(1);
        assertThat(ExcelColumn.index("AA")).isEqualTo(26);
    }

    @Test
    void rejectsInvalidColumn() {
        assertThatThrownBy(() -> ExcelColumn.index("1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("columna");
    }
}

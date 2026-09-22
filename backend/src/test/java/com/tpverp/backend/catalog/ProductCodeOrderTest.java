package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductCodeOrderTest {
    @Test
    void ordersNumericRunsWithoutOverflowAndIgnoresLeadingZeroesAndCase() {
        var codes = List.of("A10B2", "10", "A2", "2", "A10B10", "1",
                "999999999999999999999999999999999999", "1000000000000000000000000000000000000");
        assertThat(codes.stream().sorted(ProductCodeOrder.comparator()).toList()).containsExactly(
                "1", "2", "10", "999999999999999999999999999999999999",
                "1000000000000000000000000000000000000", "A2", "A10B2", "A10B10");
        assertThat(ProductCodeOrder.comparator().compare("A002B0", "a2B000")).isZero();
        assertThat(ProductCodeOrder.comparator().compare("0", "000")).isZero();
    }

    @Test
    void punctuationAndMultipleNumericRunsUseTheSameSegmentOrderAsSql() {
        assertThat(List.of("P-10", "P2A10", "P02A2", "P-2", "P10", "P2").stream()
                .sorted(ProductCodeOrder.comparator()).toList())
                .containsExactly("P2", "P02A2", "P2A10", "P10", "P-2", "P-10");
    }

    @Test
    void keepsMissingCodesAtTheEnd() {
        assertThat(Arrays.asList(null, "2", "", "1").stream()
                .sorted(ProductCodeOrder.comparator()).toList()).containsExactly("1", "2", null, "");
    }
}

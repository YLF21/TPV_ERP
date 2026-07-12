package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DiscountTypeTest {

    @Test
    void exposesOnlyCurrentCatalogDiscountModes() {
        assertThat(DiscountType.values()).containsExactly(
                DiscountType.NONE,
                DiscountType.NORMAL,
                DiscountType.MEMBER_PRICE,
                DiscountType.DISCOUNT_PRICE);
    }
}

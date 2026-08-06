package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProductQuantityPolicyTest {

    @Test
    void unitProductsOnlyAcceptWholeQuantities() {
        assertThatCode(() -> ProductQuantityPolicy.requireValid(
                ProductType.UNIT, new BigDecimal("5.000")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ProductQuantityPolicy.requireValid(
                ProductType.UNIT, new BigDecimal("4.992")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.product.unit_quantity_must_be_integer");
    }

    @Test
    void weightAndServiceProductsAcceptAtMostThreeDecimals() {
        assertThatCode(() -> ProductQuantityPolicy.requireValid(
                ProductType.WEIGHT, new BigDecimal("4.992")))
                .doesNotThrowAnyException();
        assertThatCode(() -> ProductQuantityPolicy.requireValid(
                ProductType.SERVICE, new BigDecimal("0.125")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ProductQuantityPolicy.requireValid(
                ProductType.WEIGHT, new BigDecimal("1.2345")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.document.quantity_scale");
    }
}

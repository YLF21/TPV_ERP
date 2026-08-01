package com.tpverp.backend.security.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SaleOperationSecurityRegistryTest {

    private final SaleOperationSecurityRegistry registry =
            new SaleOperationSecurityRegistry();

    @Test
    void containsEveryStableOperationExactlyOnce() {
        assertThat(registry.definitions())
                .extracting(SaleOperationDefinition::code)
                .containsExactly(SaleOperationCode.values());
        assertThat(registry.definitions()).hasSize(SaleOperationCode.values().length);
        assertThat(Arrays.stream(SaleOperationCategory.values()).toList())
                .containsExactly(
                        SaleOperationCategory.CASH,
                        SaleOperationCategory.TICKET,
                        SaleOperationCategory.PRODUCT,
                        SaleOperationCategory.DISCOUNT,
                        SaleOperationCategory.CREDIT,
                        SaleOperationCategory.PAYMENT,
                        SaleOperationCategory.PAYMENT_TERMINAL);
    }

    @Test
    void exposesConfirmedCategoriesAndSecurityDefaults() {
        assertDefinition(
                SaleOperationCode.OPEN_CASH_DRAWER,
                SaleOperationCategory.CASH,
                true,
                false);
        assertDefinition(
                SaleOperationCode.CLOSE_CASH_SESSION,
                SaleOperationCategory.CASH,
                false,
                true);
        assertDefinition(
                SaleOperationCode.GENERATE_PRODUCT_EAN,
                SaleOperationCategory.PRODUCT,
                true,
                false);
        assertDefinition(
                SaleOperationCode.RETURN_TICKET,
                SaleOperationCategory.TICKET,
                false,
                false);
        assertDefinition(
                SaleOperationCode.MANUAL_RETURN_WITHOUT_TICKET,
                SaleOperationCategory.TICKET,
                true,
                false);
        assertDefinition(
                SaleOperationCode.APPLY_SALE_DISCOUNT,
                SaleOperationCategory.DISCOUNT,
                true,
                false);
        assertDefinition(
                SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT,
                SaleOperationCategory.PAYMENT,
                true,
                false);
        assertDefinition(
                SaleOperationCode.CONFIRM_TRANSFER_PAYMENT,
                SaleOperationCategory.PAYMENT,
                true,
                false);
        assertDefinition(
                SaleOperationCode.PAYMENT_TERMINAL_REFUND,
                SaleOperationCategory.PAYMENT_TERMINAL,
                true,
                true);
    }

    private void assertDefinition(
            SaleOperationCode code,
            SaleOperationCategory category,
            boolean requirePermission,
            boolean requirePassword) {
        var definition = registry.require(code);
        assertThat(definition.category()).isEqualTo(category);
        assertThat(definition.defaultRequirePermission()).isEqualTo(requirePermission);
        assertThat(definition.defaultRequirePassword()).isEqualTo(requirePassword);
        assertThat(definition.permissions()).isNotEmpty();
    }
}

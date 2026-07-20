package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTerminalRefundLineSelectionTest {
    @Test
    void canonicalFormIsStableAndRoundTripsForCrashRecovery() {
        var first = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var second = UUID.fromString("22222222-2222-2222-2222-222222222222");
        var value = PaymentTerminalRefundLineSelection.canonical(List.of(
                new PaymentTerminalRefundLineSelection(second, new BigDecimal("2.500")),
                new PaymentTerminalRefundLineSelection(first, BigDecimal.ONE)));

        assertThat(value).isEqualTo(first + "=1;" + second + "=2.5");
        assertThat(PaymentTerminalRefundLineSelection.parse(value)).containsExactly(
                new PaymentTerminalRefundLineSelection(first, BigDecimal.ONE),
                new PaymentTerminalRefundLineSelection(second, new BigDecimal("2.5")));
    }

    @Test
    void rejectsDuplicatesAndInvalidQuantities() {
        var line = UUID.randomUUID();
        assertThatThrownBy(() -> PaymentTerminalRefundLineSelection.canonical(List.of(
                new PaymentTerminalRefundLineSelection(line, BigDecimal.ONE),
                new PaymentTerminalRefundLineSelection(line, BigDecimal.ONE))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentTerminalRefundLineSelection(line, new BigDecimal("0.000")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

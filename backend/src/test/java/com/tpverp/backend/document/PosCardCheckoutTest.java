package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.terminal.CardTerminalResult;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PosCardCheckoutTest {

    @Test
    void recordsAndReplaysADeclinedResultWithoutDocument() {
        var checkout = PosCardCheckout.reserve(UUID.randomUUID(), UUID.randomUUID(), "hash", new BigDecimal("12.10"));
        checkout.complete(new CardTerminalResult(PaymentTerminalOperationStatus.DECLINED, "REF", null, "Rechazado"), null, null);

        assertThat(checkout.toResult()).extracting(
                PosCardService.Result::status,
                PosCardService.Result::total,
                PosCardService.Result::reference,
                PosCardService.Result::ticketId)
                .containsExactly(PaymentTerminalOperationStatus.DECLINED, new BigDecimal("12.10"), "REF", null);
    }

    @Test
    void rejectsCompletingAnAlreadyCompletedCheckout() {
        var checkout = PosCardCheckout.reserve(UUID.randomUUID(), UUID.randomUUID(), "hash", BigDecimal.ONE);
        checkout.complete(new CardTerminalResult(PaymentTerminalOperationStatus.TIMEOUT, null, null, "Timeout"), null, null);

        assertThatThrownBy(() -> checkout.complete(
                new CardTerminalResult(PaymentTerminalOperationStatus.APPROVED, "r", "a", "ok"),
                UUID.randomUUID(), "T-1"))
                .isInstanceOf(IllegalStateException.class);
    }
}

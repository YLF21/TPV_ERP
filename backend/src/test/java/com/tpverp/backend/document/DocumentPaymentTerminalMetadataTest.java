package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.terminal.PaymentCardMode;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentPaymentTerminalMetadataTest {

    @Test
    void rejectsIntegratedCardPaymentThatIsNotApproved() {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 4), UUID.randomUUID(), BigDecimal.ZERO);
        var method = new PaymentMethod(UUID.randomUUID(), "TARJETA", true);

        assertThatThrownBy(() -> new DocumentPayment(
                document, method, 1, new BigDecimal("25.00"), true,
                null, null, null, "AUTH-1", Instant.parse("2026-07-04T12:00:00Z"),
                PaymentCardMode.INTEGRATED,
                PaymentTerminalProvider.REDSYS_TPV_PC,
                PaymentTerminalOperationStatus.TIMEOUT,
                null, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.payment_terminal.integrated_payment_not_approved");
    }
}

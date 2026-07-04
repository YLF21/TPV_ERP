package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentViewTest {

    @Test
    void includesPaymentDetailsWithVoucherCode() {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 6, 17), UUID.randomUUID(), BigDecimal.ZERO);
        var method = new PaymentMethod(UUID.randomUUID(), "VALE", true);
        document.addPayment(new DocumentPayment(
                document, method, 1, new BigDecimal("10.00"), true,
                null, null, "vabc123", Instant.parse("2026-06-17T12:00:00Z")));

        var view = DocumentView.from(document);

        assertThat(view.payments()).hasSize(1);
        assertThat(view.payments().getFirst().methodName()).isEqualTo("VALE");
        assertThat(view.payments().getFirst().voucherCode()).isEqualTo("VABC123");
    }

    @Test
    void includesCardTerminalMetadataWithoutSensitiveCardData() {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 4), UUID.randomUUID(), BigDecimal.ZERO);
        var method = new PaymentMethod(UUID.randomUUID(), "TARJETA", true);
        document.addPayment(new DocumentPayment(
                document, method, 1, new BigDecimal("25.00"), true,
                null, null, null, "AUTH-1", Instant.parse("2026-07-04T12:00:00Z"),
                com.tpverp.backend.terminal.PaymentCardMode.INTEGRATED,
                com.tpverp.backend.terminal.PaymentTerminalProvider.REDSYS_TPV_PC,
                com.tpverp.backend.terminal.PaymentTerminalOperationStatus.APPROVED,
                "A1B2C3", UUID.randomUUID()));

        var view = DocumentView.from(document);

        assertThat(view.payments().getFirst().cardMode()).isEqualTo("INTEGRATED");
        assertThat(view.payments().getFirst().paymentTerminalProvider()).isEqualTo("REDSYS_TPV_PC");
        assertThat(view.payments().getFirst().paymentTerminalStatus()).isEqualTo("APPROVED");
        assertThat(view.payments().getFirst().cardAuthorizationCode()).isEqualTo("A1B2C3");
    }
}

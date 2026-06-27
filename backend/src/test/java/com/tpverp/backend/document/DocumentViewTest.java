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
}

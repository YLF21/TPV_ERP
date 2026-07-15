package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TicketPrintViewTest {

    @Test
    void rejectsUnconfirmedDocumentWithLocalizedMessageKey() {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 15), UUID.randomUUID(), BigDecimal.ZERO);

        assertThatThrownBy(() -> TicketPrintView.from(document))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.document.print_ticket_requires_confirmed_document");
    }

    @Test
    void buildsAuthoritativePrintableSnapshotFromConfirmedTicket() {
        var companyId = UUID.randomUUID();
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 15), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, BigDecimal.valueOf(2), "A-1", "Cafe",
                null, BigDecimal.valueOf(3.50), BigDecimal.ZERO, true,
                "IVA", BigDecimal.valueOf(21)));
        document.confirm("001-260715-000001", UUID.randomUUID(),
                Instant.parse("2026-07-15T10:15:30Z"), false);
        var cash = new PaymentMethod(companyId, "EFECTIVO", true);
        document.addPayment(new DocumentPayment(
                document, cash, 1, BigDecimal.valueOf(7), true,
                BigDecimal.TEN, BigDecimal.valueOf(3),
                Instant.parse("2026-07-15T10:15:30Z")));

        var view = TicketPrintView.from(document);

        assertThat(view.documentNumber()).isEqualTo("001-260715-000001");
        assertThat(view.issuedAt()).isEqualTo(Instant.parse("2026-07-15T10:15:30Z"));
        assertThat(view.lines()).singleElement().satisfies(line -> {
            assertThat(line.name()).isEqualTo("Cafe");
            assertThat(line.quantity()).isEqualByComparingTo("2");
            assertThat(line.price()).isEqualByComparingTo("3.50");
            assertThat(line.total()).isEqualByComparingTo("7.00");
        });
        assertThat(view.payments()).singleElement().satisfies(payment -> {
            assertThat(payment.method()).isEqualTo("EFECTIVO");
            assertThat(payment.amount()).isEqualByComparingTo("7.00");
        });
        assertThat(view.total()).isEqualByComparingTo("7.00");
    }
}

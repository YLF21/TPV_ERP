package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SalesDocumentDetailViewTest {

    @Test
    void includesDocumentLinesSortedByPositionWithTheirAmounts() {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.ALBARAN_VENTA,
                LocalDate.of(2026, 8, 7), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 2, new BigDecimal("1.000"), "P-2", "Segundo",
                null, new BigDecimal("20.00"), BigDecimal.ZERO, true, "IVA", new BigDecimal("21.00")));
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, new BigDecimal("2.000"), "P-1", "Primero",
                null, new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA", new BigDecimal("21.00")));

        var detail = SalesDocumentDetailView.from(document);

        assertThat(detail.lines()).extracting(SalesDocumentDetailView.LineView::code)
                .containsExactly("P-1", "P-2");
        assertThat(detail.lines().getFirst().quantity()).isEqualByComparingTo("2.000");
        assertThat(detail.lines().getFirst().unitPrice()).isEqualByComparingTo("10.00");
        assertThat(detail.lines().getFirst().total()).isEqualByComparingTo("20.00");
        assertThat(detail.total()).isEqualByComparingTo("40.00");
    }

    @Test
    void includesTheOriginTicketWhenAnInvoiceWasConvertedFromIt() {
        var invoice = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.FACTURA_VENTA,
                LocalDate.of(2026, 8, 7), UUID.randomUUID(), BigDecimal.ZERO);
        var ticket = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 7), UUID.randomUUID(), BigDecimal.ZERO);
        var detail = SalesDocumentDetailView.from(invoice, ticket);

        assertThat(detail.originTicket()).isEqualTo(
                new SalesDocumentDetailView.RelatedDocumentView(
                        ticket.getId(), null));
    }
}

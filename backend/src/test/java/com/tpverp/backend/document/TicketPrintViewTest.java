package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
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
                document, UUID.randomUUID(), 1, BigDecimal.valueOf(2), "A-1",
                "8430000000010", "Cafe", null, BigDecimal.valueOf(3.50), BigDecimal.ZERO, true,
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
            assertThat(line.code()).isEqualTo("A-1");
            assertThat(line.barcode()).isEqualTo("8430000000010");
            assertThat(line.quantity()).isEqualByComparingTo("2");
            assertThat(line.price()).isEqualByComparingTo("3.50");
            assertThat(line.total()).isEqualByComparingTo("7.00");
        });
        assertThat(view.payments()).singleElement().satisfies(payment -> {
            assertThat(payment.method()).isEqualTo("EFECTIVO");
            assertThat(payment.amount()).isEqualByComparingTo("7.00");
        });
        assertThat(view.total()).isEqualByComparingTo("7.00");
        assertThat(view.checkoutDiscountTotal()).isZero();

        var branded = view.withPresentation("Gracias por su compra", "data:image/png;base64,AA==");
        assertThat(branded.observations()).isEqualTo("Gracias por su compra");
        assertThat(branded.logo()).isEqualTo("data:image/png;base64,AA==");

        var rendered = branded.withRenderedDocument(
                "%PDF-ticket".getBytes(StandardCharsets.UTF_8),
                "PNG-ticket".getBytes(StandardCharsets.UTF_8));
        assertThat(rendered.ticketRenderedPdf().contentType()).isEqualTo("application/pdf");
        assertThat(Base64.getDecoder().decode(rendered.ticketRenderedPdf().base64()))
                .isEqualTo("%PDF-ticket".getBytes(StandardCharsets.UTF_8));
        assertThat(rendered.ticketRenderedImage().contentType()).isEqualTo("image/png");
        assertThat(Base64.getDecoder().decode(rendered.ticketRenderedImage().base64()))
                .isEqualTo("PNG-ticket".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void printsAConfirmedReceivableTicketWhileItIsPendingPartialOrPaid() {
        var companyId = UUID.randomUUID();
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 12), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, BigDecimal.ONE, "P-1", "Producto",
                null, BigDecimal.TEN, BigDecimal.ZERO, true,
                "IVA", new BigDecimal("21")));
        document.setParties(UUID.randomUUID(), null, null);
        document.markTicketReceivable();
        document.confirm("001-260812-000001", UUID.randomUUID(),
                Instant.parse("2026-08-12T12:00:00Z"), false);

        assertThat(document.getEstado()).isEqualTo(DocumentStatus.PENDIENTE);
        assertThat(TicketPrintView.from(document).total()).isEqualByComparingTo("10.00");

        var cash = new PaymentMethod(companyId, "EFECTIVO", true);
        document.addPayment(new DocumentPayment(
                document, cash, 1, new BigDecimal("4.00"), true,
                new BigDecimal("4.00"), BigDecimal.ZERO,
                Instant.parse("2026-08-12T12:01:00Z")));
        document.updatePaymentStatus();
        assertThat(document.getEstado()).isEqualTo(DocumentStatus.PARCIAL);
        assertThat(TicketPrintView.from(document).payments()).hasSize(1);

        document.addPayment(new DocumentPayment(
                document, cash, 2, new BigDecimal("6.00"), false,
                new BigDecimal("6.00"), BigDecimal.ZERO,
                Instant.parse("2026-08-12T12:02:00Z")));
        document.updatePaymentStatus();
        assertThat(document.getEstado()).isEqualTo(DocumentStatus.PAGADO);
        assertThat(TicketPrintView.from(document).payments()).hasSize(2);
    }

    @Test
    void printsF11OnceInTheSummaryInsteadOfAsFiscalAllocationLines() {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 9), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, BigDecimal.ONE, "A-1", "Articulo",
                null, new BigDecimal("20.00"), BigDecimal.ZERO, true,
                "IVA", new BigDecimal("21.00")));
        document.addLine(DocumentLine.manualDiscount(
                document, 2, new BigDecimal("-6.00"), true,
                "IVA", new BigDecimal("21.00")));
        document.addLine(DocumentLine.manualDiscount(
                document, 3, new BigDecimal("-4.00"), true,
                "IVA", new BigDecimal("21.00")));
        document.confirm("001-260809-000001", UUID.randomUUID(),
                Instant.parse("2026-08-09T10:15:30Z"), false);

        var view = TicketPrintView.from(document);

        assertThat(view.lines()).extracting(TicketPrintView.Line::name)
                .containsExactly("Articulo");
        assertThat(view.checkoutDiscountTotal()).isEqualByComparingTo("10.00");
        assertThat(view.total()).isEqualByComparingTo("10.00");
    }

    @Test
    void exchangeReceiptCombinesReturnedAndSoldLinesButPrintsOnlyCollectedMoney() {
        var companyId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var issuedAt = Instant.parse("2026-08-05T10:15:30Z");
        var refund = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 5), userId, BigDecimal.ZERO);
        refund.addLine(new DocumentLine(
                refund, UUID.randomUUID(), 1, BigDecimal.ONE.negate(), "OLD", "Devuelto",
                null, new BigDecimal("100.00"), BigDecimal.ZERO, true,
                "IVA", new BigDecimal("21")));
        refund.confirm("001-260805-000001", userId, issuedAt, false);

        var sale = new CommercialDocument(
                refund.getTiendaId(), refund.getAlmacenId(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 5), userId, BigDecimal.ZERO);
        sale.addLine(new DocumentLine(
                sale, UUID.randomUUID(), 1, BigDecimal.ONE, "NEW", "Comprado",
                null, new BigDecimal("101.10"), BigDecimal.ZERO, true,
                "IVA", new BigDecimal("21")));
        sale.confirm("001-260805-000002", userId, issuedAt, false);
        var cash = new PaymentMethod(companyId, "EFECTIVO", true);
        var compensation = new PaymentMethod(
                companyId, PaymentMethodService.EXCHANGE_COMPENSATION_METHOD, true);
        sale.addPayment(new DocumentPayment(
                sale, cash, 1, new BigDecimal("1.10"), true,
                new BigDecimal("1.10"), BigDecimal.ZERO, issuedAt));
        sale.addPayment(new DocumentPayment(
                sale, compensation, 2, new BigDecimal("100.00"), false,
                null, null, issuedAt));

        var view = TicketPrintView.fromExchange(sale, refund);

        assertThat(view.lines()).extracting(TicketPrintView.Line::name)
                .containsExactly("Devuelto", "Comprado");
        assertThat(view.payments()).singleElement().satisfies(payment -> {
            assertThat(payment.method()).isEqualTo("EFECTIVO");
            assertThat(payment.amount()).isEqualByComparingTo("1.10");
        });
        assertThat(view.total()).isEqualByComparingTo("1.10");
    }
}

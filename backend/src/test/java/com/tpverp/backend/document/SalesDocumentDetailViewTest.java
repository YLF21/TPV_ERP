package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SalesDocumentDetailViewTest {

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER_PERCENT", "MANUAL_PERCENT"})
    void exposesPersistedAdjustmentOriginWithoutChangingHistoricalTextOrAmounts(String type) {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 9, 8), UUID.randomUUID(), BigDecimal.ZERO);
        var product = new DocumentLine(document, UUID.randomUUID(), 1, BigDecimal.ONE,
                "P-1", "Articulo", null, new BigDecimal("1.42"), BigDecimal.ZERO,
                true, "IVA", new BigDecimal("7.00"));
        document.addLine(product);
        var adjustment = new DocumentAdjustment(document, type, 1, new BigDecimal("3.00"),
                new BigDecimal("1.42"), new BigDecimal("0.04"), null, Instant.EPOCH,
                null, null, null);
        document.addAdjustment(adjustment);
        var discount = DocumentLine.special(document, 2, "DESCUENTO DOCUMENTAL",
                new BigDecimal("-0.04"), true, "IVA", new BigDecimal("7.00"),
                null, null, null, DocumentLineType.DOCUMENT_DISCOUNT);
        discount.linkDocumentAdjustment(adjustment.getId(), product.getId());
        document.addLine(discount);

        var detail = SalesDocumentDetailView.from(document);

        assertThat(detail.lines().getFirst().documentAdjustmentType()).isNull();
        assertThat(detail.lines().getLast().documentAdjustmentType()).isEqualTo(type);
        assertThat(detail.lines().getLast().code()).isEqualTo("DESCUENTO DOCUMENTAL");
        assertThat(detail.lines().getLast().name()).isEqualTo("DESCUENTO DOCUMENTAL");
        assertThat(detail.lines().getLast().total()).isEqualByComparingTo("-0.04");
        assertThat(detail.total()).isEqualByComparingTo("1.38");
        assertThat(discount.getNombre()).isEqualTo("DESCUENTO DOCUMENTAL");
        assertThat(document.getTotal()).isEqualByComparingTo("1.38");
    }

    @Test
    void resolvesEachLineByItsAdjustmentIdAndDoesNotGuessMissingOrigins() {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 9, 8), UUID.randomUUID(), BigDecimal.ZERO);
        var member = new DocumentAdjustment(document, "MEMBER_PERCENT", 1, BigDecimal.ONE,
                BigDecimal.TEN, BigDecimal.ONE, null, Instant.EPOCH, null, null, null);
        var manual = new DocumentAdjustment(document, "MANUAL_PERCENT", 2, BigDecimal.ONE,
                BigDecimal.TEN, BigDecimal.ONE, null, Instant.EPOCH, null, null, null);
        document.addAdjustment(member);
        document.addAdjustment(manual);
        var origins = new UUID[]{manual.getId(), null, member.getId(), UUID.randomUUID()};
        for (int index = 0; index < origins.length; index++) {
            var line = DocumentLine.special(document, index + 1, "DESCUENTO DOCUMENTAL",
                    BigDecimal.ONE.negate(), true, "IVA", BigDecimal.ZERO,
                    null, null, null, DocumentLineType.DOCUMENT_DISCOUNT);
            line.linkDocumentAdjustment(origins[index], null);
            document.addLine(line);
        }
        var product = new DocumentLine(document, UUID.randomUUID(), 5, BigDecimal.ONE,
                "DESCUENTO DOCUMENTAL", "Producto", null, BigDecimal.TEN, BigDecimal.ZERO,
                true, "IVA", BigDecimal.ZERO);
        product.linkDocumentAdjustment(member.getId(), null);
        document.addLine(product);

        assertThat(SalesDocumentDetailView.from(document).lines())
                .extracting(SalesDocumentDetailView.LineView::documentAdjustmentType)
                .containsExactly("MANUAL_PERCENT", null, "MEMBER_PERCENT", null, null);
    }

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

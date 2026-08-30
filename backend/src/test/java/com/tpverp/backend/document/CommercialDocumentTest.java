package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommercialDocumentTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-16T10:15:30Z");

    @Test
    void receivableStateUsesOnlyRealPayments() {
        var document = saleInvoice(new BigDecimal("100.00"));

        document.confirm("FV-1", USER_ID, NOW, false);
        document.setDueDate(LocalDate.of(2026, 8, 15));

        assertThat(document.getPendingTotal()).isEqualByComparingTo("100.00");
        assertThat(document.getEstado()).isEqualTo(DocumentStatus.PENDIENTE);
        assertThat(document.getDueDate()).isEqualTo(LocalDate.of(2026, 8, 15));
    }

    @Test
    void paymentCanCarryAnIdempotencyRequestId() {
        var document = saleInvoice(new BigDecimal("100.00"));
        var method = new PaymentMethod(UUID.randomUUID(), "TRANSFERENCIA", true);
        var requestId = UUID.randomUUID();

        var payment = new DocumentPayment(
                document, method, 1, new BigDecimal("25.00"), true,
                null, null, null, "bank-ref", NOW,
                null, null, null, null, null, requestId);

        assertThat(payment.getRequestId()).isEqualTo(requestId);
    }

    @Test
    void invoiceConvertedFromPaidTicketInheritsSettlementWithoutDuplicatingPayment() {
        var method = new PaymentMethod(UUID.randomUUID(), "EFECTIVO", true);
        var ticket = documentWithTotal(CommercialDocumentType.TICKET,
                new BigDecimal("100.00"));
        ticket.addPayment(new DocumentPayment(
                ticket, method, 1, new BigDecimal("100.00"), true,
                new BigDecimal("100.00"), BigDecimal.ZERO, null, null, NOW));
        ticket.confirm("T-1", USER_ID, NOW, true);
        var invoice = new CommercialDocument(
                ticket.getTiendaId(), ticket.getAlmacenId(),
                CommercialDocumentType.FACTURA_VENTA,
                ticket.getFecha(), USER_ID, BigDecimal.ZERO);
        invoice.addLine(new DocumentLine(
                invoice, UUID.randomUUID(), 1, BigDecimal.ONE, "P-1", "Product", null,
                new BigDecimal("100.00"), BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO));
        invoice.setNumTicket(ticket.getNumero());
        invoice.confirm("FV-1", USER_ID, NOW, false);

        invoice.settleFromPaidTicket(ticket);

        assertThat(invoice.getEstado()).isEqualTo(DocumentStatus.PAGADO);
        assertThat(invoice.getPaidTotal()).isEqualByComparingTo("100.00");
        assertThat(invoice.getPendingTotal()).isZero();
        assertThat(invoice.getPagos()).isEmpty();
        assertThat(invoice.isSettledByOrigin()).isTrue();
    }

    @Test
    void internalCommentIsNormalizedAndCannotChangeAfterConfirmation() {
        var document = saleInvoice(new BigDecimal("100.00"));

        document.setInternalComment("  Entregar por la tarde  ");

        assertThat(document.getComentarioInterno()).isEqualTo("Entregar por la tarde");

        document.confirm("FV-1", USER_ID, NOW, false);
        assertThatThrownBy(() -> document.setInternalComment("Otro comentario"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("borrador");
    }

    @Test
    void internalCommentCannotExceedItsPersistentLimit() {
        var document = saleInvoice(new BigDecimal("100.00"));

        assertThatThrownBy(() -> document.setInternalComment("x".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
    }

    @Test
    void replacesEditableDraftContentWithoutChangingItsIdentity() {
        var storeId = UUID.randomUUID();
        var original = new CommercialDocument(
                storeId, UUID.randomUUID(), CommercialDocumentType.FACTURA_VENTA,
                LocalDate.of(2026, 8, 10), USER_ID, BigDecimal.ZERO);
        original.setParties(UUID.randomUUID(), null, null);
        original.setDueDate(LocalDate.of(2026, 9, 9));
        original.setInternalComment("Comentario anterior");
        original.addLine(new DocumentLine(
                original, UUID.randomUUID(), 1, BigDecimal.ONE, "P-1", "Anterior", null,
                new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA", new BigDecimal("21")));
        var originalId = original.getId();
        var originalCreatedAt = original.getCreadoEn();

        var newCustomerId = UUID.randomUUID();
        var replacement = new CommercialDocument(
                storeId, UUID.randomUUID(), CommercialDocumentType.ALBARAN_VENTA,
                LocalDate.of(2026, 8, 12), USER_ID, new BigDecimal("5.00"), true);
        replacement.setParties(newCustomerId, null, null);
        replacement.setDueDate(LocalDate.of(2026, 9, 11));
        replacement.setInternalComment("Comentario conservado");
        var replacementLine = new DocumentLine(
                replacement, UUID.randomUUID(), 1, new BigDecimal("2"),
                "P-2", "Nombre temporal", null, new BigDecimal("20.00"),
                BigDecimal.ZERO, true, "IVA", new BigDecimal("21"));
        replacementLine.assignTemporaryOverrides(true, true);
        replacement.addLine(replacementLine);

        original.replacePendingSaleDraft(replacement);

        assertThat(original.getId()).isEqualTo(originalId);
        assertThat(original.getCreadoEn()).isEqualTo(originalCreatedAt);
        assertThat(original.getTipo()).isEqualTo(CommercialDocumentType.ALBARAN_VENTA);
        assertThat(original.getFecha()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(original.getDueDate()).isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(original.getClienteId()).isEqualTo(newCustomerId);
        assertThat(original.isWholesaleMode()).isTrue();
        assertThat(original.getComentarioInterno()).isEqualTo("Comentario conservado");
        assertThat(original.getTotal()).isEqualByComparingTo("38.00");
        assertThat(original.getLineas()).singleElement().satisfies(line -> {
            assertThat(line.getNombre()).isEqualTo("Nombre temporal");
            assertThat(line.isTemporaryNameOverride()).isTrue();
            assertThat(line.isTemporaryPriceOverride()).isTrue();
        });
    }

    @Test
    void replacesDraftAndRemapsDocumentAdjustmentLinksToCopiedLines() {
        var storeId = UUID.randomUUID();
        var original = new CommercialDocument(storeId, UUID.randomUUID(),
                CommercialDocumentType.FACTURA_VENTA, LocalDate.of(2026, 8, 10),
                USER_ID, BigDecimal.ZERO);
        var replacement = new CommercialDocument(storeId, UUID.randomUUID(),
                CommercialDocumentType.FACTURA_VENTA, LocalDate.of(2026, 8, 11),
                USER_ID, BigDecimal.ZERO);
        var product = new DocumentLine(replacement, UUID.randomUUID(), 1, BigDecimal.ONE,
                "P-1", "Producto", "VENTA", new BigDecimal("10.00"),
                BigDecimal.ZERO, true, "IVA", new BigDecimal("21"));
        replacement.addLine(product);
        DocumentPercentDiscountAllocator.apply(replacement, new BigDecimal("10.00"),
                Set.of(product.getProductoId()));
        var discount = replacement.getLineas().getLast();
        var adjustment = new DocumentAdjustment(replacement, "MANUAL_PERCENT", 1,
                new BigDecimal("10.00"), new BigDecimal("10.00"),
                discount.getTotal().abs(), null, NOW, null, null, null);
        replacement.addAdjustment(adjustment);
        discount.linkDocumentAdjustment(adjustment.getId(), product.getId());

        original.replacePendingSaleDraft(replacement);

        assertThat(original.getAjustes()).singleElement().satisfies(restored -> {
            assertThat(restored.getId()).isNotEqualTo(adjustment.getId());
        });
        var copiedDiscount = original.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.DOCUMENT_DISCOUNT)
                .findFirst().orElseThrow();
        assertThat(copiedDiscount.getDocumentAdjustmentId())
                .isEqualTo(original.getAjustes().getFirst().getId())
                .isNotEqualTo(adjustment.getId());
        assertThat(copiedDiscount.getSourceLineId())
                .isEqualTo(original.getLineas().getFirst().getId())
                .isNotEqualTo(product.getId());
    }

    @Test
    void replacesDraftAndPreservesZeroRoundedDocumentAdjustment() {
        var storeId = UUID.randomUUID();
        var original = new CommercialDocument(storeId, UUID.randomUUID(),
                CommercialDocumentType.ALBARAN_VENTA, LocalDate.of(2026, 8, 10),
                USER_ID, BigDecimal.ZERO);
        var replacement = new CommercialDocument(storeId, UUID.randomUUID(),
                CommercialDocumentType.ALBARAN_VENTA, LocalDate.of(2026, 8, 11),
                USER_ID, BigDecimal.ZERO);
        replacement.addLine(new DocumentLine(
                replacement, UUID.randomUUID(), 1, BigDecimal.ONE,
                "P-1", "Producto", "VENTA", new BigDecimal("0.01"),
                BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO));
        replacement.addAdjustment(new DocumentAdjustment(
                replacement, "MANUAL_PERCENT", 1, BigDecimal.ONE,
                new BigDecimal("0.01"), BigDecimal.ZERO, USER_ID, NOW,
                null, null, null));

        original.replacePendingSaleDraft(replacement);

        assertThat(original.getAjustes()).singleElement().satisfies(adjustment -> {
            assertThat(adjustment.getPorcentaje()).isEqualByComparingTo("1.00");
            assertThat(adjustment.getBaseElegible()).isEqualByComparingTo("0.01");
            assertThat(adjustment.getImporteAplicado()).isZero();
        });
        assertThat(original.getLineas()).singleElement().satisfies(line ->
                assertThat(line.getTotal()).isEqualByComparingTo("0.01"));
    }

    private static CommercialDocument saleInvoice(BigDecimal total) {
        return documentWithTotal(CommercialDocumentType.FACTURA_VENTA, total);
    }

    private static CommercialDocument documentWithTotal(
            CommercialDocumentType type, BigDecimal total) {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), type, LocalDate.of(2026, 7, 16),
                USER_ID, BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, BigDecimal.ONE, "P-1", "Product", null,
                total, BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO));
        return document;
    }
}

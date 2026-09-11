package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentSyncPayloadFactoryTest {
    private final DocumentRelationRepository relations = mock(DocumentRelationRepository.class);
    private final DocumentAttributionResolver attributions = mock(DocumentAttributionResolver.class);
    private final DocumentSyncPayloadFactory factory = new DocumentSyncPayloadFactory(relations, attributions);
    private static final Instant CONFIRMED_AT = Instant.parse("2026-09-10T10:00:00Z");

    @Test
    void preservesLegacyHeaderLinesAndPaymentsAndAddsPersistedAttribution() {
        var document = document(CommercialDocumentType.TICKET);
        UUID localCustomer = UUID.randomUUID();
        UUID localSupplier = UUID.randomUUID();
        UUID originTerminal = UUID.randomUUID();
        UUID confirmer = UUID.randomUUID();
        document.setParties(localCustomer, localSupplier, null);
        document.assignOriginTerminal(originTerminal);
        var method = new PaymentMethod(UUID.randomUUID(), "EFECTIVO", true);
        document.addPayment(new DocumentPayment(document, method, 1, document.getTotal(), true,
                new BigDecimal("10.00"), new BigDecimal("6.26"), null, "payment-reference", CONFIRMED_AT));
        document.confirm("T-001", confirmer, CONFIRMED_AT, false);
        when(attributions.resolve(List.of(document))).thenReturn(Map.of(document.getId(),
                new DocumentAttributionResolver.Attribution(confirmer, "Operador", originTerminal, "Caja 001", CONFIRMED_AT)));
        when(relations.findOutgoingForSync(document.getId(), document.getTiendaId())).thenReturn(List.of());

        Map<String, Object> payload = factory.create(document, 5);

        assertThat(payload).containsEntry("schemaVersion", 2).containsEntry("sourceRevision", 5L)
                .containsEntry("tipo", "TICKET").containsEntry("numero", "T-001")
                .containsEntry("estado", "CONFIRMADO").containsEntry("fecha", "2026-09-10")
                .containsEntry("clienteId", localCustomer.toString()).containsEntry("proveedorId", localSupplier.toString())
                .containsEntry("almacenId", document.getAlmacenId().toString())
                .containsEntry("descuentoGlobal", "0.00").containsEntry("subtotal", "3.09")
                .containsEntry("impuestos", "0.65").containsEntry("total", "3.74").containsEntry("moneda", "EUR")
                .containsEntry("creadoPor", document.getCreadoPor().toString())
                .containsEntry("confirmadoPor", confirmer.toString()).containsEntry("anuladoPor", null)
                .containsEntry("creadoEn", document.getCreadoEn().toString())
                .containsEntry("confirmadoEn", CONFIRMED_AT.toString()).containsEntry("anuladoEn", null)
                .containsEntry("terminalOrigenId", originTerminal.toString()).containsEntry("fechaVencimiento", null)
                .containsEntry("usuarioNombre", "Operador").containsEntry("terminalOrigenNombre", "Caja 001")
                .containsEntry("settledByOrigin", false).containsEntry("relaciones", List.of());
        assertThat(payload).doesNotContainKeys("saasCustomerId", "pendiente", "paidTotal");
        Map<?, ?> line = (Map<?, ?>) ((List<?>) payload.get("lineas")).getFirst();
        assertThat(line.keySet()).extracting(Object::toString).containsExactlyInAnyOrder("productoId", "tipoLinea", "promocionId",
                "cuponPromocionalId", "posicion", "codigo", "nombre", "tarifa", "cantidad", "precioUnitario",
                "descuento", "impuestosIncluidos", "regimenImpuesto", "porcentajeImpuesto", "base", "impuesto", "total");
        assertThat(line.get("precioUnitario")).isEqualTo("1.234");
        assertThat(line.get("cantidad")).isEqualTo("2.500");
        assertThat(line.get("base")).isEqualTo("3.09");
        Map<?, ?> payment = (Map<?, ?>) ((List<?>) payload.get("pagos")).getFirst();
        assertThat(payment.keySet()).extracting(Object::toString).containsExactlyInAnyOrder("metodoPagoId", "metodoPago", "posicion", "importe",
                "principal", "entregado", "cambio", "voucherCode", "referencia", "terminalPagoModo",
                "terminalPagoProvider", "terminalPagoEstado", "autorizacionTarjeta", "terminalCobroId");
        assertThat(payment.get("metodoPagoId")).isEqualTo(method.getId().toString());
        assertThat(payment.get("importe")).isEqualTo("3.74");
        assertThat(payment.get("entregado")).isEqualTo("10.00");
        assertThat(payment.get("cambio")).isEqualTo("6.26");
        assertThat(payment.get("referencia")).isEqualTo("payment-reference");
    }

    @Test
    void includesCompleteOutgoingRelationshipsWithLocalOriginIds() {
        var document = document(CommercialDocumentType.FACTURA_VENTA);
        UUID ticketId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        var outgoing = List.of(
                relation(DocumentRelationType.COMPENSA, refundId, document.getTiendaId()),
                relation(DocumentRelationType.FACTURA_DE, ticketId, document.getTiendaId()));
        when(relations.findOutgoingForSync(document.getId(), document.getTiendaId())).thenReturn(outgoing);

        Map<String, Object> payload = factory.create(document, 1);

        assertThat(payload.get("relaciones")).isEqualTo(List.of(
                Map.of("tipo", "COMPENSA", "origenId", refundId.toString()),
                Map.of("tipo", "FACTURA_DE", "origenId", ticketId.toString())));
        verify(relations).findOutgoingForSync(document.getId(), document.getTiendaId());
    }

    @Test
    void refusesCrossStoreRelationshipsInsteadOfPublishingAnIncompleteList() {
        var document = document(CommercialDocumentType.FACTURA_VENTA);
        var outgoing = List.of(relation(DocumentRelationType.FACTURA_DE, UUID.randomUUID(), UUID.randomUUID()));
        when(relations.findOutgoingForSync(document.getId(), document.getTiendaId())).thenReturn(outgoing);

        assertThatThrownBy(() -> factory.create(document, 1)).isInstanceOf(IllegalStateException.class)
                .hasMessage("La procedencia de una relacion documental no coincide");
    }

    @Test
    void rejectsNonPositiveRevisionBeforeReadingRelationships() {
        var document = document(CommercialDocumentType.TICKET);
        for (long revision : new long[] {0, -1, Long.MIN_VALUE}) {
            assertThatThrownBy(() -> factory.create(document, revision)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Revision documental no valida");
        }
        verifyNoInteractions(relations);
    }

    @Test
    void copiesCancellationIdentityWithoutChangingItsSignedAmounts() {
        var document = document(CommercialDocumentType.TICKET);
        document.confirm("T-1", UUID.randomUUID(), CONFIRMED_AT, false);
        UUID canceller = UUID.randomUUID();
        Instant cancelledAt = CONFIRMED_AT.plusSeconds(60);
        document.cancel(canceller, cancelledAt, "test");
        when(relations.findOutgoingForSync(document.getId(), document.getTiendaId())).thenReturn(List.of());

        Map<String, Object> payload = factory.create(document, 2);

        assertThat(payload).containsEntry("estado", "ANULADO")
                .containsEntry("anuladoPor", canceller.toString()).containsEntry("anuladoEn", cancelledAt.toString())
                .containsEntry("total", "3.74");
    }

    @Test
    void copiesSettlementFromOriginWithoutInventingAnotherPaymentOrDebt() {
        var ticket = document(CommercialDocumentType.TICKET);
        var method = new PaymentMethod(UUID.randomUUID(), "EFECTIVO", true);
        ticket.addPayment(new DocumentPayment(ticket, method, 1, ticket.getTotal(), true,
                null, null, null, null, CONFIRMED_AT));
        ticket.confirm("T-1", UUID.randomUUID(), CONFIRMED_AT, false);
        var invoice = new CommercialDocument(ticket.getTiendaId(), ticket.getAlmacenId(),
                CommercialDocumentType.FACTURA_VENTA, ticket.getFecha(), UUID.randomUUID(), BigDecimal.ZERO);
        invoice.addLine(new DocumentLine(invoice, UUID.randomUUID(), 1, new BigDecimal("2.500"), "P1", "Product", null,
                new BigDecimal("1.234"), BigDecimal.ZERO, false, "IVA", new BigDecimal("21.00")));
        invoice.setNumTicket(ticket.getNumero());
        invoice.setDueDate(LocalDate.of(2026, 10, 10));
        invoice.confirm("FV-1", UUID.randomUUID(), CONFIRMED_AT, false);
        invoice.settleFromPaidTicket(ticket);
        var outgoing = List.of(relation(DocumentRelationType.FACTURA_DE, ticket.getId(), ticket.getTiendaId()));
        when(relations.findOutgoingForSync(invoice.getId(), invoice.getTiendaId())).thenReturn(outgoing);

        var payload = factory.create(invoice, 1);

        assertThat(payload).containsEntry("settledByOrigin", true).containsEntry("estado", "PAGADO")
                .containsEntry("fechaVencimiento", "2026-10-10").containsEntry("pagos", List.of());
        assertThat(payload).doesNotContainKeys("pendiente", "paidTotal");
    }

    private CommercialDocument document(CommercialDocumentType type) {
        var document = new CommercialDocument(UUID.randomUUID(), UUID.randomUUID(), type,
                LocalDate.of(2026, 9, 10), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(document, UUID.randomUUID(), 1, new BigDecimal("2.500"), "P1", "Product", null,
                new BigDecimal("1.234"), BigDecimal.ZERO, false, "IVA", new BigDecimal("21.00")));
        return document;
    }

    private DocumentRelationRepository.SyncRelation relation(DocumentRelationType type, UUID originId, UUID storeId) {
        var relation = mock(DocumentRelationRepository.SyncRelation.class);
        when(relation.getType()).thenReturn(type);
        when(relation.getOriginId()).thenReturn(originId);
        when(relation.getOriginStoreId()).thenReturn(storeId);
        return relation;
    }
}

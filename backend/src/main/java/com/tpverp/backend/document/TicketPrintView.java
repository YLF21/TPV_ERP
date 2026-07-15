package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TicketPrintView(
        UUID documentId,
        String documentNumber,
        Instant issuedAt,
        List<Line> lines,
        List<Payment> payments,
        BigDecimal total) {

    public static TicketPrintView from(CommercialDocument document) {
        if (document.getEstado() != DocumentStatus.CONFIRMADO
                || document.getConfirmadoEn() == null) {
            throw new IllegalArgumentException(
                    "message.document.print_ticket_requires_confirmed_document");
        }
        return new TicketPrintView(
                document.getId(), document.getNumero(), document.getConfirmadoEn(),
                document.getLineas().stream()
                        .map(line -> new Line(line.getNombre(), line.getCantidad(),
                                line.getPrecioUnitario(), line.getTotal()))
                        .toList(),
                document.getPagos().stream()
                        .map(payment -> new Payment(
                                payment.getMetodoPago().getNombre(), payment.getImporte()))
                        .toList(),
                document.getTotal());
    }

    public record Line(String name, BigDecimal quantity, BigDecimal price, BigDecimal total) {}

    public record Payment(String method, BigDecimal amount) {}
}

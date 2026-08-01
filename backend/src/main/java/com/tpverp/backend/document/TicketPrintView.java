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
        BigDecimal total,
        BigDecimal baseTotal,
        BigDecimal taxTotal) {

    public TicketPrintView(
            UUID documentId,
            String documentNumber,
            Instant issuedAt,
            List<Line> lines,
            List<Payment> payments,
            BigDecimal total) {
        this(documentId, documentNumber, issuedAt, lines, payments, total, null, null);
    }

    public static TicketPrintView from(CommercialDocument document) {
        return from(document, List.of());
    }

    public static TicketPrintView from(CommercialDocument document, List<RefundTender> refundPayouts) {
        if (document.getEstado() != DocumentStatus.CONFIRMADO
                || document.getConfirmadoEn() == null) {
            throw new IllegalArgumentException(
                    "message.document.print_ticket_requires_confirmed_document");
        }
        return new TicketPrintView(
                document.getId(), document.getNumero(), document.getConfirmadoEn(),
                document.getLineas().stream()
                        .map(line -> new Line(line.getNombre(), line.getCantidad(),
                                line.getPrecioUnitario(), line.getTotal(),
                                line.getSerialNumbers()))
                        .toList(),
                refundPayouts == null || refundPayouts.isEmpty()
                        ? document.getPagos().stream()
                                .map(payment -> new Payment(
                                        payment.getMetodoPago().getNombre(), payment.getImporte()))
                                .toList()
                        : refundPayouts.stream()
                                .map(payout -> new Payment(
                                        switch (payout.getType()) {
                                            case CASH -> "EFECTIVO";
                                            case CARD -> "TARJETA";
                                            case VOUCHER -> "VALE";
                                        }, payout.getAmount().negate()))
                                .toList(),
                document.getTotal(),
                document.getBaseTotal(),
                document.getImpuestoTotal());
    }

    public record Line(
            String name,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal total,
            List<String> serialNumbers) {
        public Line(String name, BigDecimal quantity, BigDecimal price, BigDecimal total) {
            this(name, quantity, price, total, List.of());
        }
    }

    public record Payment(String method, BigDecimal amount) {}
}

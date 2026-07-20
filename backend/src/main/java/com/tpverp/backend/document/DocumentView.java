package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record DocumentView(
        UUID id,
        CommercialDocumentType tipo,
        DocumentStatus estado,
        String numero,
        LocalDate fecha,
        UUID customerId,
        String customerName,
        LocalDate dueDate,
        BigDecimal base,
        BigDecimal impuesto,
        BigDecimal total,
        BigDecimal paidTotal,
        BigDecimal pendingTotal,
        String numTicket,
        String qrUrl,
        boolean origenStock,
        UUID usuarioId,
        String usuarioNombre,
        UUID terminalOrigenId,
        String terminalOrigenNombre,
        Instant ocurridoEn,
        List<PaymentView> payments) {

    public static DocumentView from(CommercialDocument document) {
        return from(document, null, null,
                DocumentAttributionResolver.Attribution.empty(document));
    }

    public static DocumentView from(CommercialDocument document, String qrUrl) {
        return from(document, null, qrUrl,
                DocumentAttributionResolver.Attribution.empty(document));
    }

    public static DocumentView from(
            CommercialDocument document,
            String qrUrl,
            DocumentAttributionResolver.Attribution attribution) {
        return from(document, null, qrUrl, attribution);
    }

    static DocumentView from(
            CommercialDocument document, String customerName, String qrUrl) {
        return from(document, customerName, qrUrl,
                DocumentAttributionResolver.Attribution.empty(document));
    }

    static DocumentView from(
            CommercialDocument document,
            String customerName,
            String qrUrl,
            DocumentAttributionResolver.Attribution attribution) {
        return new DocumentView(
                document.getId(), document.getTipo(), document.getEstado(),
                document.getNumero(), document.getFecha(), document.getClienteId(), customerName,
                document.getDueDate(), document.getBaseTotal(),
                document.getImpuestoTotal(), document.getTotal(), document.getPaidTotal(),
                document.getPendingTotal(),
                document.getNumTicket(), qrUrl, document.isOrigenStock(),
                attribution.userId(), attribution.userName(),
                attribution.terminalId(), attribution.terminalName(), attribution.occurredAt(),
                document.getPagos().stream()
                        .sorted(Comparator.comparingInt(DocumentPayment::getPosicion))
                        .map(PaymentView::from)
                        .toList());
    }

    public record PaymentView(
            UUID methodId,
            String methodName,
            int position,
            BigDecimal amount,
            boolean principal,
            BigDecimal delivered,
            BigDecimal change,
            String voucherCode,
            String reference,
            String cardMode,
            String paymentTerminalProvider,
            String paymentTerminalStatus,
            String cardAuthorizationCode,
            UUID paymentTerminalId) {

        static PaymentView from(DocumentPayment payment) {
            return new PaymentView(
                    payment.getMetodoPago().getId(),
                    payment.getMetodoPago().getNombre(),
                    payment.getPosicion(),
                    payment.getImporte(),
                    payment.isPrincipal(),
                    payment.getEntregado(),
                    payment.getCambio(),
                    payment.getVoucherCode(),
                    payment.getReferencia(),
                    nullableEnum(payment.getCardMode()),
                    nullableEnum(payment.getPaymentTerminalProvider()),
                    nullableEnum(payment.getPaymentTerminalStatus()),
                    payment.getCardAuthorizationCode(),
                    payment.getPaymentTerminalId());
        }
    }

    private static String nullableEnum(Enum<?> value) {
        return value == null ? null : value.name();
    }
}

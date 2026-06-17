package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record DocumentView(
        UUID id,
        TipoDocumento tipo,
        EstadoDocumento estado,
        String numero,
        LocalDate fecha,
        BigDecimal base,
        BigDecimal impuesto,
        BigDecimal total,
        String numTicket,
        String qrUrl,
        boolean origenStock,
        List<PaymentView> payments) {

    public static DocumentView from(Documento document) {
        return from(document, null);
    }

    public static DocumentView from(Documento document, String qrUrl) {
        return new DocumentView(
                document.getId(), document.getTipo(), document.getEstado(),
                document.getNumero(), document.getFecha(), document.getBaseTotal(),
                document.getImpuestoTotal(), document.getTotal(),
                document.getNumTicket(), qrUrl, document.isOrigenStock(),
                document.getPagos().stream()
                        .sorted(Comparator.comparingInt(DocumentoPago::getPosicion))
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
            String voucherCode) {

        static PaymentView from(DocumentoPago payment) {
            return new PaymentView(
                    payment.getMetodoPago().getId(),
                    payment.getMetodoPago().getNombre(),
                    payment.getPosicion(),
                    payment.getImporte(),
                    payment.isPrincipal(),
                    payment.getEntregado(),
                    payment.getCambio(),
                    payment.getVoucherCode());
        }
    }
}

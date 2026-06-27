package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentCommand(
        UUID metodoPagoId,
        BigDecimal importe,
        boolean principal,
        BigDecimal entregado,
        BigDecimal cambio,
        String voucherCode,
        String reference) {

    public PaymentCommand(
            UUID metodoPagoId,
            BigDecimal importe,
            boolean principal,
            BigDecimal entregado,
            BigDecimal cambio) {
        this(metodoPagoId, importe, principal, entregado, cambio, null, null);
    }

    public PaymentCommand(
            UUID metodoPagoId,
            BigDecimal importe,
            boolean principal,
            BigDecimal entregado,
            BigDecimal cambio,
            String voucherCode) {
        this(metodoPagoId, importe, principal, entregado, cambio, voucherCode, null);
    }
}

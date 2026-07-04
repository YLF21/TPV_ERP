package com.tpverp.backend.document;

import com.tpverp.backend.terminal.PaymentCardMode;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalProvider;
import java.math.BigDecimal;
import java.util.UUID;

public record PaymentCommand(
        UUID metodoPagoId,
        BigDecimal importe,
        boolean principal,
        BigDecimal entregado,
        BigDecimal cambio,
        String voucherCode,
        String reference,
        PaymentCardMode cardMode,
        PaymentTerminalProvider paymentTerminalProvider,
        PaymentTerminalOperationStatus paymentTerminalStatus,
        String cardAuthorizationCode,
        UUID paymentTerminalId) {

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

    public PaymentCommand(
            UUID metodoPagoId,
            BigDecimal importe,
            boolean principal,
            BigDecimal entregado,
            BigDecimal cambio,
            String voucherCode,
            String reference) {
        this(metodoPagoId, importe, principal, entregado, cambio, voucherCode, reference,
                null, null, null, null, null);
    }
}

package com.tpverp.backend.document;

import com.tpverp.backend.terminal.PaymentCardMode;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
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
        UUID paymentTerminalId,
        UUID requestId,
        String comment,
        LocalDate transferDate) {

    public PaymentCommand(
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
            UUID paymentTerminalId,
            UUID requestId,
            String comment) {
        this(metodoPagoId, importe, principal, entregado, cambio, voucherCode, reference,
                cardMode, paymentTerminalProvider, paymentTerminalStatus,
                cardAuthorizationCode, paymentTerminalId, requestId, comment, null);
    }

    public PaymentCommand(
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
            UUID paymentTerminalId,
            UUID requestId) {
        this(metodoPagoId, importe, principal, entregado, cambio, voucherCode, reference,
                cardMode, paymentTerminalProvider, paymentTerminalStatus,
                cardAuthorizationCode, paymentTerminalId, requestId, null, null);
    }

    public PaymentCommand(
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
        this(metodoPagoId, importe, principal, entregado, cambio, voucherCode, reference,
                cardMode, paymentTerminalProvider, paymentTerminalStatus,
                cardAuthorizationCode, paymentTerminalId, null, null, null);
    }

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
                null, null, null, null, null, null, null, null);
    }
}

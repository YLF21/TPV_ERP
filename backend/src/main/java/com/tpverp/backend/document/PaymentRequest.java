package com.tpverp.backend.document;

import com.tpverp.backend.terminal.PaymentCardMode;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalProvider;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PaymentRequest(@NotEmpty List<Item> pagos) {

    // Maps every payment while preserving order and principal flag.
    public List<PaymentCommand> toCommands() {
        return pagos.stream().map(Item::toCommand).toList();
    }

    public record Item(
            @NotNull UUID metodoPagoId,
            @NotNull BigDecimal importe,
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
            UUID paymentTerminalOperationId) {

        public Item(
                UUID metodoPagoId,
                BigDecimal importe,
                boolean principal,
                BigDecimal entregado,
                BigDecimal cambio,
                String voucherCode) {
            this(metodoPagoId, importe, principal, entregado, cambio, voucherCode, null,
                    null, null, null, null, null, null, null);
        }

        public Item(
                UUID metodoPagoId,
                BigDecimal importe,
                boolean principal,
                BigDecimal entregado,
                BigDecimal cambio,
                String voucherCode,
                String reference) {
            this(metodoPagoId, importe, principal, entregado, cambio, voucherCode, reference,
                    null, null, null, null, null, null, null);
        }

        public Item(
                UUID metodoPagoId, BigDecimal importe, boolean principal,
                BigDecimal entregado, BigDecimal cambio, String voucherCode, String reference,
                PaymentCardMode cardMode, PaymentTerminalProvider paymentTerminalProvider,
                PaymentTerminalOperationStatus paymentTerminalStatus,
                String cardAuthorizationCode, UUID paymentTerminalId) {
            this(metodoPagoId, importe, principal, entregado, cambio, voucherCode, reference,
                    cardMode, paymentTerminalProvider, paymentTerminalStatus,
                    cardAuthorizationCode, paymentTerminalId, null, null);
        }

        PaymentCommand toCommand() {
            return new PaymentCommand(
                    metodoPagoId, importe, principal, entregado, cambio, voucherCode, reference,
                    cardMode, paymentTerminalProvider, paymentTerminalStatus,
                    cardAuthorizationCode, paymentTerminalId, requestId);
        }
    }
}

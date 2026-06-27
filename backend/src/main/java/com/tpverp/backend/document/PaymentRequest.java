package com.tpverp.backend.document;

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
            String reference) {

        public Item(
                UUID metodoPagoId,
                BigDecimal importe,
                boolean principal,
                BigDecimal entregado,
                BigDecimal cambio,
                String voucherCode) {
            this(metodoPagoId, importe, principal, entregado, cambio, voucherCode, null);
        }

        PaymentCommand toCommand() {
            return new PaymentCommand(
                    metodoPagoId, importe, principal, entregado, cambio, voucherCode, reference);
        }
    }
}

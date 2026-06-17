package com.tpverp.backend.document;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PaymentRequest(@NotEmpty List<Item> pagos) {

    // Traduce todos los pagos preservando orden y marca principal.
    public List<PaymentCommand> toCommands() {
        return pagos.stream().map(Item::toCommand).toList();
    }

    public record Item(
            @NotNull UUID metodoPagoId,
            @NotNull BigDecimal importe,
            boolean principal,
            BigDecimal entregado,
            BigDecimal cambio,
            String voucherCode) {

        PaymentCommand toCommand() {
            return new PaymentCommand(
                    metodoPagoId, importe, principal, entregado, cambio, voucherCode);
        }
    }
}

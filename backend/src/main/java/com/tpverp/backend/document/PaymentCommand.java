package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentCommand(
        UUID metodoPagoId,
        BigDecimal importe,
        boolean principal,
        BigDecimal entregado,
        BigDecimal cambio) {
}

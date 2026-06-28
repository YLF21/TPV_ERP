package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.util.UUID;

public record SaleLineDeletionCommand(
        UUID productId,
        String code,
        String name,
        int quantity,
        BigDecimal unitPrice) {
}

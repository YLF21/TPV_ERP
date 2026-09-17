package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.util.UUID;

public record SaleLineDeletionCommand(
        UUID productId,
        String code,
        String name,
        BigDecimal quantity,
        BigDecimal unitPrice) {

    public SaleLineDeletionCommand(UUID productId, String code, String name, int quantity, BigDecimal unitPrice) {
        this(productId, code, name, BigDecimal.valueOf(quantity), unitPrice);
    }
}

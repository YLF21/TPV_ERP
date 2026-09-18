package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SaleLineDeletionView(
        UUID id,
        UUID storeId,
        UUID terminalId,
        UUID userId,
        Instant deletedAt,
        String type,
        UUID productId,
        String code,
        String name,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal total,
        Instant receivedAt) {

    public SaleLineDeletionView(UUID id, UUID storeId, UUID terminalId, UUID userId, Instant deletedAt,
            String type, UUID productId, String code, String name, int quantity,
            BigDecimal unitPrice, BigDecimal total) {
        this(id, storeId, terminalId, userId, deletedAt, type, productId, code, name,
                BigDecimal.valueOf(quantity), unitPrice, total, deletedAt);
    }
}

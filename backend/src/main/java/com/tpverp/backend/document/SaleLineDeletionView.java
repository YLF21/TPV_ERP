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
        int quantity,
        BigDecimal unitPrice,
        BigDecimal total) {
}

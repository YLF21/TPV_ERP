package com.tpverp.backend.goodscheck;

import java.time.Instant;
import java.util.UUID;

public record GoodsCheckSummary(
        UUID id,
        UUID documentId,
        String documentNumber,
        GoodsCheckStatus status,
        Instant createdAt,
        Instant closedAt,
        long lineCount,
        long differenceCount) {
}
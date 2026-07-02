package com.tpverp.backend.goodscheck;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record GoodsCheckScanRequest(
        UUID productId,
        String code,
        @NotNull BigDecimal quantity) {
}

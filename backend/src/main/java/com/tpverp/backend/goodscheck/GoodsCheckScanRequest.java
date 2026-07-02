package com.tpverp.backend.goodscheck;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record GoodsCheckScanRequest(
        UUID productId,
        String code,
        @NotNull Integer quantity) {
}

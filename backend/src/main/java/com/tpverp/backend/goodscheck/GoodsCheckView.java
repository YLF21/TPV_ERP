package com.tpverp.backend.goodscheck;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record GoodsCheckView(
        UUID id,
        UUID documentId,
        GoodsCheckStatus status,
        List<Item> todos,
        List<Item> faltantes,
        List<Item> registrados) {

    public record Item(
            UUID productId,
            String code,
            String name,
            BigDecimal expectedQuantity,
            BigDecimal registeredQuantity,
            BigDecimal missingQuantity,
            BigDecimal extraQuantity) {
    }
}

package com.tpverp.backend.goodscheck;

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
            int expectedQuantity,
            int registeredQuantity,
            int missingQuantity,
            int extraQuantity) {
    }
}

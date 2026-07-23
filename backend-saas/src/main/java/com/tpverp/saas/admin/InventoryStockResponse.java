package com.tpverp.saas.admin;

public record InventoryStockResponse(
        String warehouseCode,
        String productSku,
        String quantity) {
}

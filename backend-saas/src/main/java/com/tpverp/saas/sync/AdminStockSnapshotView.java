package com.tpverp.saas.sync;

import java.util.UUID;

public record AdminStockSnapshotView(
        UUID companyId,
        UUID storeId,
        String productId,
        String warehouseId,
        String quantity) {
}

package com.tpverp.saas.inventory;

import java.util.List;
import java.util.UUID;

public record InventoryComparison(
        InventoryReport.Snapshot previous,
        InventoryReport.Snapshot current,
        int page,
        int size,
        long total,
        Counts counts,
        Catalogue catalogue,
        List<Row> rows) {
    public enum Sort {
        PRODUCT,
        WAREHOUSE,
        STATUS,
        DELTA
    }

    public enum Direction {
        ASC,
        DESC
    }

    public record Counts(long added, long removed, long changed, long unchanged) {}

    public record Catalogue(
            long added, long removed, long activated, long deactivated, long modified) {}

    public record Row(
            UUID productId,
            String code,
            String name,
            UUID warehouseId,
            String warehouseName,
            String status,
            String previousQuantity,
            String currentQuantity,
            String delta,
            String percent) {}
}

package com.tpverp.saas.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class InventoryReport {
    private InventoryReport() {}

    public enum Direction {
        ASC,
        DESC
    }

    public enum OverviewSort {
        COMPANY,
        STORE,
        PRODUCTS,
        BALANCES,
        CAPTURED_AT
    }

    public enum HistorySort {
        CAPTURED_AT,
        PRODUCTS,
        BALANCES
    }

    public enum ProductSort {
        PRODUCT,
        CODE,
        TYPE,
        ACTIVE
    }

    public enum BalanceSort {
        PRODUCT,
        WAREHOUSE,
        QUANTITY
    }

    public enum WarehouseSort {
        WAREHOUSE,
        ACTIVE
    }

    public record Province(String province, Totals totals) {}

    public record Snapshot(
            UUID snapshotId,
            UUID installationId,
            Instant capturedAt,
            Instant completedAt,
            boolean currentInstallation,
            int productCount,
            int activeProductCount,
            int warehouseCount,
            int balanceCount,
            int positiveCount,
            int zeroCount,
            int negativeCount) {}

    public record Store(
            UUID companyId,
            String companyName,
            UUID storeId,
            String storeName,
            Snapshot snapshot) {}

    public record Totals(
            long stores,
            long reportedStores,
            long products,
            long activeProducts,
            long warehouses,
            long balances,
            long positiveCount,
            long zeroCount,
            long negativeCount) {}

    public record Overview(int page, int size, long total, Totals totals, List<Store> rows) {}

    public record Page<T>(int page, int size, long total, List<T> rows) {}

    public record Detail<T>(int page, int size, long total, Snapshot snapshot, List<T> rows) {}

    public record Product(
            UUID productId,
            String code,
            String name,
            String barcode,
            String secondaryBarcode,
            boolean active,
            String productType,
            UUID familyId,
            String familyName,
            UUID subfamilyId,
            String subfamilyName) {}

    public record Warehouse(
            UUID warehouseId, String name, boolean active, boolean defaultWarehouse) {}

    public record Balance(
            UUID productId,
            String code,
            String name,
            String productType,
            boolean active,
            UUID warehouseId,
            String warehouseName,
            String quantity) {}
}

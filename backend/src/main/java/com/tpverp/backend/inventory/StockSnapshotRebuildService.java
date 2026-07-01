package com.tpverp.backend.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockSnapshotRebuildService {

    private final StockLevelRepository stockLevels;
    private final StockMovementRepository movements;

    public StockSnapshotRebuildService(
            StockLevelRepository stockLevels,
            StockMovementRepository movements) {
        this.stockLevels = stockLevels;
        this.movements = movements;
    }

    @Transactional
    public StockSnapshotRebuildResult rebuild() {
        var snapshots = movements.sumQuantitiesByProductAndWarehouse().stream()
                .map(value -> StockLevel.snapshot(
                        value.productId(),
                        value.warehouseId(),
                        value.quantity()))
                .toList();
        stockLevels.deleteAllInBatch();
        stockLevels.saveAll(snapshots);
        return new StockSnapshotRebuildResult(snapshots.size());
    }
}

package com.tpverp.backend.inventory;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseStockSettingsRepository extends JpaRepository<WarehouseStockSettings, UUID> {
    Optional<WarehouseStockSettings> findByWarehouseIdAndStoreId(UUID warehouseId, UUID storeId);
    List<WarehouseStockSettings> findByStoreIdAndInheritsStoreSettingsTrue(UUID storeId);
    List<WarehouseStockSettings> findByStoreId(UUID storeId);
}

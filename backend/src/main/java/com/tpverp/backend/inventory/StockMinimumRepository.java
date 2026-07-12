package com.tpverp.backend.inventory;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockMinimumRepository extends JpaRepository<StockMinimum, UUID> {

    Optional<StockMinimum> findByStoreIdAndProductIdAndWarehouseId(
            UUID storeId, UUID productId, UUID warehouseId);
}

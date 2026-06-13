package com.tpverp.backend.inventory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockLevelRepository extends JpaRepository<StockLevel, UUID> {

    Optional<StockLevel> findByProductIdAndWarehouseId(UUID productId, UUID warehouseId);

    List<StockLevel> findByWarehouseId(UUID warehouseId);

    List<StockLevel> findByProductId(UUID productId);

    boolean existsByProductId(UUID productId);

    @Query("select coalesce(sum(value.cantidad), 0) from StockLevel value where value.warehouseId = :warehouseId")
    long sumQuantityByWarehouseId(@Param("warehouseId") UUID warehouseId);
}

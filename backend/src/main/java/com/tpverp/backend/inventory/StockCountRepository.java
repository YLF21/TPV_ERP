package com.tpverp.backend.inventory;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockCountRepository extends JpaRepository<StockCount, UUID> {
    List<StockCount> findByStoreIdOrderByCreatedAtDesc(UUID storeId);
    List<StockCount> findByStoreIdAndStatusOrderByCreatedAtDesc(UUID storeId, StockCountStatus status);
    Optional<StockCount> findByIdAndStoreId(UUID id, UUID storeId);
    boolean existsByStoreIdAndWarehouseIdAndStatus(UUID storeId, UUID warehouseId, StockCountStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from StockCount value where value.id=:id and value.storeId=:storeId")
    Optional<StockCount> findLockedByIdAndStoreId(@Param("id") UUID id, @Param("storeId") UUID storeId);
}

package com.tpverp.backend.inventory;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockAdjustmentHistoryRepository extends JpaRepository<StockAdjustmentHistory, UUID> {
    @Query("select min(row.createdAt) from StockAdjustmentHistory row where row.storeId = :storeId")
    Instant findFirstReportCreatedAt(UUID storeId);

    @Query("""
            select row from StockAdjustmentHistory row
            where row.storeId = :storeId
              and (:allWarehouses = true or row.warehouseId in :warehouseIds)
              and (:search is null or lower(row.code) like :search
                   or lower(row.barcode) like :search or lower(row.name) like :search
                   or lower(row.reason) like :search)
              and row.createdAt >= :from and row.createdAt < :to
            order by row.createdAt desc, row.movementId desc
            """)
    Page<StockAdjustmentHistory> pageFiltered(@Param("storeId") UUID storeId,
                                      @Param("allWarehouses") boolean allWarehouses,
                                      @Param("warehouseIds") java.util.List<UUID> warehouseIds,
                                      @Param("search") String search,
                                      @Param("from") java.time.Instant from,
                                      @Param("to") java.time.Instant to, Pageable pageable);
    default Page<StockAdjustmentHistory> page(UUID storeId, UUID warehouseId, String search,
            Instant from, Instant to, Pageable pageable) {
        return pageFiltered(storeId, warehouseId == null,
                java.util.List.of(warehouseId == null ? new UUID(0, 0) : warehouseId), search, from, to, pageable);
    }

    interface AdjustmentAuthor {
        UUID getMovementId();
        String getUserName();
    }

    @Query("""
            select history.movementId as movementId, account.userName as userName
            from StockAdjustmentHistory history
            join StockMovement movement on movement.id = history.movementId
            left join UserAccount account on account.id = movement.userId
            where history.storeId = :storeId and history.movementId in :movementIds
            """)
    java.util.List<AdjustmentAuthor> authors(@Param("storeId") UUID storeId,
                                            @Param("movementIds") java.util.List<UUID> movementIds);
}

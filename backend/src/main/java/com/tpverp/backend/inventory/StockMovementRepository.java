package com.tpverp.backend.inventory;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    List<StockMovement> findByProductIdOrderByCreatedAtDesc(UUID productId);

    @Query("""
            select new com.tpverp.backend.inventory.StockSnapshotQuantity(
                movement.productId,
                movement.warehouseId,
                sum(movement.cantidad))
            from StockMovement movement
            group by movement.productId, movement.warehouseId
            """)
    List<StockSnapshotQuantity> sumQuantitiesByProductAndWarehouse();

    boolean existsByProductId(UUID productId);

    boolean existsByDocumentId(UUID documentId);

    List<StockMovement> findByDocumentIdAndCompensationOfIdIsNull(UUID documentId);

    boolean existsByCompensationOfId(UUID movementId);

    boolean existsByWarehouseOutputId(UUID outputId);
}

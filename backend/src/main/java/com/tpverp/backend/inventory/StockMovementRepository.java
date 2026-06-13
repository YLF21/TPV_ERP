package com.tpverp.backend.inventory;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    List<StockMovement> findByProductIdOrderByCreatedAtDesc(UUID productId);

    boolean existsByProductId(UUID productId);

    boolean existsByDocumentId(UUID documentId);

    List<StockMovement> findByDocumentIdAndCompensationOfIdIsNull(UUID documentId);

    boolean existsByCompensationOfId(UUID movementId);

    boolean existsByWarehouseOutputId(UUID outputId);
}

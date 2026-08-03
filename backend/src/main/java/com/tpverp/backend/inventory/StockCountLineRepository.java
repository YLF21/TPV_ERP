package com.tpverp.backend.inventory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockCountLineRepository extends JpaRepository<StockCountLine, UUID> {
    List<StockCountLine> findByCountIdOrderByProductId(UUID countId);
    Optional<StockCountLine> findByCountIdAndProductId(UUID countId, UUID productId);
    long countByCountId(UUID countId);
}

package com.tpverp.backend.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductPriceHistoryRepository extends JpaRepository<ProductPriceHistory, UUID> {

    List<ProductPriceHistory> findByProductIdOrderByUpdatedAtDesc(UUID productId);
}

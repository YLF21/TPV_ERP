package com.tpverp.backend.catalog;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductPriceHistoryRepository extends JpaRepository<ProductPriceHistory, UUID> {

    List<ProductPriceHistory> findByProductIdOrderByUpdatedAtDesc(UUID productId);

    @Query("""
            select history
              from ProductPriceHistory history
             where history.productId in :productIds
               and history.tipo = :type
               and history.updatedAt <= :updatedAt
               and history.updatedAt = (
                    select max(latest.updatedAt)
                      from ProductPriceHistory latest
                     where latest.productId = history.productId
                       and latest.tipo = :type
                       and latest.updatedAt <= :updatedAt
               )
             order by history.productId asc, history.updatedAt desc, history.id asc
            """)
    List<ProductPriceHistory> findPriceEvidenceAtOrBefore(
            @Param("productIds") Collection<UUID> productIds,
            @Param("type") ProductPriceHistoryType type,
            @Param("updatedAt") Instant updatedAt);
}

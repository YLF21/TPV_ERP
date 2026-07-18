package com.tpverp.backend.inventory;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockLevelRepository extends JpaRepository<StockLevel, UUID> {

    Optional<StockLevel> findByProductIdAndWarehouseId(UUID productId, UUID warehouseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select stock
            from StockLevel stock
            where stock.productId = :productId
              and stock.warehouseId = :warehouseId
            """)
    Optional<StockLevel> findByProductIdAndWarehouseIdForUpdate(
            @Param("productId") UUID productId,
            @Param("warehouseId") UUID warehouseId);

    List<StockLevel> findByWarehouseId(UUID warehouseId);

    List<StockLevel> findByProductId(UUID productId);

    List<StockLevel> findByProductIdIn(Collection<UUID> productIds);

    boolean existsByProductId(UUID productId);

    @Query("select coalesce(sum(value.cantidad), 0) from StockLevel value where value.warehouseId = :warehouseId")
    BigDecimal sumQuantityByWarehouseId(@Param("warehouseId") UUID warehouseId);

    @Query("select coalesce(sum(value.cantidad), 0) from StockLevel value where value.productId = :productId")
    BigDecimal sumQuantityByProductId(@Param("productId") UUID productId);
}

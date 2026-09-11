package com.tpverp.backend.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

    List<Warehouse> findByStoreIdOrderByNombre(UUID storeId);

    List<Warehouse> findByStoreIdAndIdIn(UUID storeId, List<UUID> ids);

    Optional<Warehouse> findByStoreIdAndPredeterminadoTrue(UUID storeId);

    boolean existsByStoreIdAndNombreIgnoreCase(UUID storeId, String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select warehouse from Warehouse warehouse where warehouse.id = :id and warehouse.storeId = :storeId")
    Optional<Warehouse> findByIdAndStoreIdForUpdate(UUID id, UUID storeId);
}

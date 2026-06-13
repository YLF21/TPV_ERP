package com.tpverp.backend.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

    List<Warehouse> findByStoreIdOrderByNombre(UUID storeId);

    Optional<Warehouse> findByStoreIdAndPredeterminadoTrue(UUID storeId);

    boolean existsByStoreIdAndNombreIgnoreCase(UUID storeId, String name);
}

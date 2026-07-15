package com.tpverp.backend.inventory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseInputRepository extends JpaRepository<WarehouseInput, UUID> {

    @EntityGraph(attributePaths = "lines")
    List<WarehouseInput> findByStoreIdOrderByFechaDesc(UUID storeId);

    Optional<WarehouseInput> findByStoreIdAndNumero(UUID storeId, String number);
}

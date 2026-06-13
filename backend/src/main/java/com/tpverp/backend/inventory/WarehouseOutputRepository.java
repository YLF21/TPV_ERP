package com.tpverp.backend.inventory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseOutputRepository extends JpaRepository<WarehouseOutput, UUID> {

    List<WarehouseOutput> findByStoreIdOrderByFechaDesc(UUID storeId);

    Optional<WarehouseOutput> findByStoreIdAndNumero(UUID storeId, String number);
}

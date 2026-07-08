package com.tpverp.backend.inventory;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseInputLineRepository extends JpaRepository<WarehouseInputLine, UUID> {

    List<WarehouseInputLine> findByInputId(UUID inputId);
}

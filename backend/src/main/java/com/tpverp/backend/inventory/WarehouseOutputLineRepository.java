package com.tpverp.backend.inventory;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseOutputLineRepository extends JpaRepository<WarehouseOutputLine, UUID> {

    List<WarehouseOutputLine> findByOutputId(UUID outputId);
}

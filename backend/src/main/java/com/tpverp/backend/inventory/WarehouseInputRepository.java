package com.tpverp.backend.inventory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

public interface WarehouseInputRepository extends JpaRepository<WarehouseInput, UUID> {

    @EntityGraph(attributePaths = "lines")
    List<WarehouseInput> findByStoreIdOrderByFechaDesc(UUID storeId);

    @EntityGraph(attributePaths = "lines")
    @Query("""
            select input
            from WarehouseInput input
            where input.storeId = :storeId
              and (:cursorDate is null
                or input.fecha < :cursorDate
                or (input.fecha = :cursorDate and input.id < :cursorId))
            order by input.fecha desc, input.id desc
            """)
    List<WarehouseInput> findPageByStoreId(
            UUID storeId,
            LocalDate cursorDate,
            UUID cursorId,
            Pageable pageable);

    Optional<WarehouseInput> findByStoreIdAndNumero(UUID storeId, String number);
}

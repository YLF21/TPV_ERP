package com.tpverp.backend.inventory;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WarehouseOutputRepository extends JpaRepository<WarehouseOutput, UUID> {

    @EntityGraph(attributePaths = "lines")
    List<WarehouseOutput> findByStoreIdOrderByFechaDesc(UUID storeId);

    @EntityGraph(attributePaths = "lines")
    @Query("""
            select output
            from WarehouseOutput output
            where output.storeId = :storeId
              and (:cursorDate is null
                or output.fecha < :cursorDate
                or (output.fecha = :cursorDate and output.id < :cursorId))
            order by output.fecha desc, output.id desc
            """)
    List<WarehouseOutput> findPageByStoreId(
            UUID storeId,
            LocalDate cursorDate,
            UUID cursorId,
            Pageable pageable);

    Optional<WarehouseOutput> findByStoreIdAndNumero(UUID storeId, String number);
}

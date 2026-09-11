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

    @Query("select min(output.fecha) from WarehouseOutput output where output.storeId = :storeId")
    LocalDate findFirstReportDate(UUID storeId);

    default List<WarehouseOutput> findReportPageInRange(
            UUID storeId, LocalDate dateFrom, LocalDate dateTo,
            LocalDate cursorDate, UUID cursorId, Pageable pageable) {
        var ids = findReportIdsInRange(storeId, dateFrom, dateTo, cursorDate, cursorId, pageable);
        if (ids.isEmpty()) return List.of();
        var byId = findReportDocumentsByIds(storeId, ids).stream().collect(
                java.util.stream.Collectors.toMap(WarehouseOutput::getId, output -> output));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    @Query("""
            select output.id from WarehouseOutput output
            where output.storeId = :storeId
              and (cast(:dateFrom as date) is null or output.fecha >= :dateFrom)
              and (cast(:dateTo as date) is null or output.fecha <= :dateTo)
              and (cast(:cursorDate as date) is null or output.fecha < :cursorDate
                or (output.fecha = :cursorDate and output.id < :cursorId))
            order by output.fecha desc, output.id desc
            """)
    List<UUID> findReportIdsInRange(UUID storeId, LocalDate dateFrom, LocalDate dateTo,
            LocalDate cursorDate, UUID cursorId, Pageable pageable);

    @EntityGraph(attributePaths = "lines")
    @Query("select output from WarehouseOutput output where output.storeId = :storeId and output.id in :ids")
    List<WarehouseOutput> findReportDocumentsByIds(UUID storeId, List<UUID> ids);
}

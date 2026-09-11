package com.tpverp.backend.inventory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

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

    @EntityGraph(attributePaths = "lines")
    @Query("""
            select distinct input
            from WarehouseInput input
            where input.storeId = :storeId
              and (:type is null or input.documentType = :type)
              and (:cursorDate is null
                or input.fecha < :cursorDate
                or (input.fecha = :cursorDate and input.id < :cursorId))
            order by input.fecha desc, input.id desc
            """)
    List<WarehouseInput> findPageByStoreIdAndType(
            UUID storeId,
            WarehouseInputDocumentType type,
            LocalDate cursorDate,
            UUID cursorId,
            Pageable pageable);

    @EntityGraph(attributePaths = "lines")
    Optional<WarehouseInput> findByIdAndStoreId(UUID id, UUID storeId);

    @Query("select min(input.fecha) from WarehouseInput input where input.storeId = :storeId and (:type is null or input.documentType = :type)")
    LocalDate findFirstReportDate(UUID storeId, WarehouseInputDocumentType type);

    default List<WarehouseInput> findReportPageInRange(
            UUID storeId, WarehouseInputDocumentType type, LocalDate dateFrom, LocalDate dateTo,
            LocalDate cursorDate, UUID cursorId, Pageable pageable) {
        var ids = findReportIdsInRange(storeId, type, dateFrom, dateTo, cursorDate, cursorId, pageable);
        if (ids.isEmpty()) return List.of();
        var byId = findReportDocumentsByIds(storeId, ids).stream().collect(
                java.util.stream.Collectors.toMap(WarehouseInput::getId, input -> input));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    @Query("""
            select input.id from WarehouseInput input
            where input.storeId = :storeId and (:type is null or input.documentType = :type)
              and (cast(:dateFrom as date) is null or input.fecha >= :dateFrom)
              and (cast(:dateTo as date) is null or input.fecha <= :dateTo)
              and (cast(:cursorDate as date) is null or input.fecha < :cursorDate
                or (input.fecha = :cursorDate and input.id < :cursorId))
            order by input.fecha desc, input.id desc
            """)
    List<UUID> findReportIdsInRange(UUID storeId, WarehouseInputDocumentType type,
            LocalDate dateFrom, LocalDate dateTo, LocalDate cursorDate, UUID cursorId, Pageable pageable);

    @EntityGraph(attributePaths = "lines")
    @Query("select input from WarehouseInput input where input.storeId = :storeId and input.id in :ids")
    List<WarehouseInput> findReportDocumentsByIds(UUID storeId, List<UUID> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "lines")
    @Query("select distinct input from WarehouseInput input where input.id = :id and input.storeId = :storeId")
    Optional<WarehouseInput> findByIdAndStoreIdForUpdate(UUID id, UUID storeId);

    @Query("""
            select count(input) > 0
            from WarehouseInput input join input.sourceDeliveryNoteIds sourceId
            where sourceId = :deliveryNoteId
              and input.id <> :invoiceId
            """)
    boolean existsOtherInvoiceForDeliveryNote(UUID invoiceId, UUID deliveryNoteId);

    Optional<WarehouseInput> findByStoreIdAndNumero(UUID storeId, String number);
}

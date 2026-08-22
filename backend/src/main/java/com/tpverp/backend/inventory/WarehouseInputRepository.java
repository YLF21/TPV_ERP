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

    @Query("""
            select count(input) > 0
            from WarehouseInput input join input.sourceDeliveryNoteIds sourceId
            where sourceId = :deliveryNoteId
              and input.id <> :invoiceId
            """)
    boolean existsOtherInvoiceForDeliveryNote(UUID invoiceId, UUID deliveryNoteId);

    Optional<WarehouseInput> findByStoreIdAndNumero(UUID storeId, String number);
}

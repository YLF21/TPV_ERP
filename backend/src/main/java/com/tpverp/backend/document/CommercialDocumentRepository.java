package com.tpverp.backend.document;

import java.util.Collection;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommercialDocumentRepository extends JpaRepository<CommercialDocument, UUID> {
    java.util.Optional<CommercialDocument> findByPaymentTerminalRefundOperationId(UUID operationId);

    @EntityGraph(attributePaths = {"pagos", "pagos.metodoPago"})
    List<CommercialDocument> findAllByTiendaIdAndTipoInOrderByFechaDesc(
            UUID tiendaId, Collection<CommercialDocumentType> tipos);

    @EntityGraph(attributePaths = {"pagos", "pagos.metodoPago"})
    List<CommercialDocument> findAllByTiendaIdAndFecha(UUID tiendaId, LocalDate fecha);

    @EntityGraph(attributePaths = {"lineas"})
    @Query("""
            select document
            from CommercialDocument document
            where document.tiendaId = :storeId
              and document.fecha between :from and :to
              and document.tipo in :types
              and document.estado not in (
                  com.tpverp.backend.document.DocumentStatus.BORRADOR,
                  com.tpverp.backend.document.DocumentStatus.ANULADO)
            order by document.fecha desc
            """)
    List<CommercialDocument> findTopSalesDocuments(
            @Param("storeId") UUID storeId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("types") Collection<CommercialDocumentType> types);
}

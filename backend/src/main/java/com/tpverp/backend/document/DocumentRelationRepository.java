package com.tpverp.backend.document;

import java.util.UUID;
import java.util.Set;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRelationRepository
        extends JpaRepository<DocumentRelation, DocumentRelationId> {

    boolean existsByOrigen_IdAndTipo(UUID originId, DocumentRelationType type);

    @Query("""
            select relation.origen.id from DocumentRelation relation
            where relation.documento.tiendaId = :storeId
              and relation.tipo = com.tpverp.backend.document.DocumentRelationType.FACTURA_DE
              and relation.documento.fecha <= :asOfDate
              and relation.documento.estado not in (
                com.tpverp.backend.document.DocumentStatus.BORRADOR,
                com.tpverp.backend.document.DocumentStatus.ANULADO)
            """)
    Set<UUID> findInvoicedOriginIds(
            @Param("storeId") UUID storeId,
            @Param("asOfDate") java.time.LocalDate asOfDate);
}

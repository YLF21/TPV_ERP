package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRelationRepository
        extends JpaRepository<DocumentRelation, DocumentRelationId> {

    boolean existsByOrigen_IdAndTipo(UUID originId, DocumentRelationType type);

    boolean existsByDocumento_IdAndTipo(UUID documentId, DocumentRelationType type);

    boolean existsByDocumento_IdAndOrigen_IdAndTipo(
            UUID documentId, UUID originId, DocumentRelationType type);

    @Query("""
            select relation.documento.id from DocumentRelation relation
            where relation.origen.id = :originId
              and relation.tipo = :type
            """)
    Optional<UUID> findDocumentIdByOriginIdAndType(
            @Param("originId") UUID originId,
            @Param("type") DocumentRelationType type);

    @Query("""
            select relation.origen.id from DocumentRelation relation
            where relation.documento.id = :documentId
              and relation.tipo = :type
            """)
    Optional<UUID> findOriginId(
            @Param("documentId") UUID documentId,
            @Param("type") DocumentRelationType type);

    @Query("""
            select relation.origen.id as originId,
                   relation.documento.id as documentId,
                   relation.documento.numero as documentNumber,
                   relation.documento.total as documentTotal,
                   relation.documento.tipo as documentType
            from DocumentRelation relation
            where relation.origen.tiendaId = :storeId
              and relation.documento.tiendaId = :storeId
              and relation.origen.id in :originIds
              and relation.tipo = :type
              and relation.documento.estado not in (
                com.tpverp.backend.document.DocumentStatus.BORRADOR,
                com.tpverp.backend.document.DocumentStatus.ANULADO)
            order by relation.documento.fecha asc, relation.documento.id asc
            """)
    List<RelatedDocument> findActiveRelatedDocuments(
            @Param("storeId") UUID storeId,
            @Param("originIds") Collection<UUID> originIds,
            @Param("type") DocumentRelationType type);

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

    interface RelatedDocument {
        UUID getOriginId();

        UUID getDocumentId();

        String getDocumentNumber();

        BigDecimal getDocumentTotal();

        CommercialDocumentType getDocumentType();
    }
}

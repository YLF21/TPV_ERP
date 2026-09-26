package com.tpverp.backend.promotion;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PromotionRepository extends JpaRepository<Promotion, UUID> {

    List<Promotion> findByEmpresaIdAndEstado(UUID empresaId, PromotionStatus estado);

    List<Promotion> findByEmpresaIdOrderByNombreAsc(UUID empresaId);

    @Query("""
            select line.promotionVersionId as promotionId, count(distinct document.id) as usageCount
            from DocumentLine line
            join line.documento document
            join Store store on store.id = document.tiendaId
            where store.empresa.id = :empresaId
              and line.promotionVersionId in :promotionIds
              and line.lineType = com.tpverp.backend.document.DocumentLineType.PROMOTION
              and document.tipo in (
                  com.tpverp.backend.document.CommercialDocumentType.TICKET,
                  com.tpverp.backend.document.CommercialDocumentType.ALBARAN_VENTA,
                  com.tpverp.backend.document.CommercialDocumentType.FACTURA_VENTA)
              and document.estado not in (
                  com.tpverp.backend.document.DocumentStatus.BORRADOR,
                  com.tpverp.backend.document.DocumentStatus.ANULADO)
            group by line.promotionVersionId
            """)
    List<PromotionUsageCount> findUsageCounts(
            @Param("empresaId") UUID empresaId,
            @Param("promotionIds") Collection<UUID> promotionIds);

    Optional<Promotion> findByIdAndEmpresaId(UUID id, UUID empresaId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select promotion
            from Promotion promotion
            where promotion.id = :id
              and promotion.empresaId = :empresaId
            """)
    Optional<Promotion> findByIdAndEmpresaIdForUpdate(
            @Param("id") UUID id,
            @Param("empresaId") UUID empresaId);

    @Query("""
            select promotion
            from Promotion promotion
            where promotion.empresaId = :empresaId
              and promotion.estado = com.tpverp.backend.promotion.PromotionStatus.ACTIVE
              and (promotion.id = :rootId or promotion.versionOrigenId = :rootId)
            """)
    List<Promotion> findActiveLineage(
            @Param("empresaId") UUID empresaId,
            @Param("rootId") UUID rootId);

    interface PromotionUsageCount {
        UUID getPromotionId();

        Long getUsageCount();
    }
}

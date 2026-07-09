package com.tpverp.backend.promotion;

import jakarta.persistence.LockModeType;
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

    Optional<Promotion> findByIdAndEmpresaId(UUID id, UUID empresaId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select promotion
            from Promotion promotion
            where promotion.empresaId = :empresaId
              and promotion.estado = com.tpverp.backend.promotion.PromotionStatus.ACTIVE
              and (promotion.id = :rootId or promotion.versionOrigenId = :rootId)
            """)
    List<Promotion> findActiveLineageForUpdate(
            @Param("empresaId") UUID empresaId,
            @Param("rootId") UUID rootId);
}

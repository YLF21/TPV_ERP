package com.tpverp.backend.promotion;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PromotionTargetRepository extends JpaRepository<PromotionTarget, UUID> {

    List<PromotionTarget> findByPromocionIdIn(Collection<UUID> promotionIds);

    List<PromotionTarget> findByPromocionId(UUID promotionId);

    @Query("select case when count(target) > 0 then true else false end "
            + "from PromotionTarget target join Promotion promotion "
            + "on promotion.id = target.promocionId "
            + "where promotion.empresaId = :companyId and target.tipo in "
            + "(com.tpverp.backend.promotion.PromotionTargetType.FAMILY, "
            + "com.tpverp.backend.promotion.PromotionTargetType.SUBFAMILY) "
            + "and target.objetivoId in :targetIds")
    boolean existsFamilyOrSubfamilyReference(UUID companyId, Collection<UUID> targetIds);

    @Query("select target.promocionId as promotionId, promotion.nombre as promotionName, "
            + "target.tipo as type, target.objetivoId as targetId "
            + "from PromotionTarget target join Promotion promotion on promotion.id = target.promocionId "
            + "where promotion.empresaId = :companyId and target.tipo in "
            + "(com.tpverp.backend.promotion.PromotionTargetType.FAMILY, "
            + "com.tpverp.backend.promotion.PromotionTargetType.SUBFAMILY) "
            + "and target.objetivoId in :targetIds")
    List<PromotionTargetReference> findFamilyOrSubfamilyReferences(
            UUID companyId, Collection<UUID> targetIds);
}

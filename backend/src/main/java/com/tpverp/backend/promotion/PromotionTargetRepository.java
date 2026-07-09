package com.tpverp.backend.promotion;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromotionTargetRepository extends JpaRepository<PromotionTarget, UUID> {

    List<PromotionTarget> findByPromocionIdIn(Collection<UUID> promotionIds);
}

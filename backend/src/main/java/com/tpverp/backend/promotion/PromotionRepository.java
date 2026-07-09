package com.tpverp.backend.promotion;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromotionRepository extends JpaRepository<Promotion, UUID> {

    List<Promotion> findByEmpresaIdAndEstado(UUID empresaId, PromotionStatus estado);
}

package com.tpverp.backend.promotion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromotionRepository extends JpaRepository<Promotion, UUID> {

    List<Promotion> findByEmpresaIdAndEstado(UUID empresaId, PromotionStatus estado);

    List<Promotion> findByEmpresaIdOrderByNombreAsc(UUID empresaId);

    Optional<Promotion> findByIdAndEmpresaId(UUID id, UUID empresaId);
}

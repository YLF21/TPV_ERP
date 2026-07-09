package com.tpverp.backend.promotion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromotionalCouponRepository extends JpaRepository<PromotionalCoupon, UUID> {

    List<PromotionalCoupon> findByEmpresaId(UUID empresaId);

    List<PromotionalCoupon> findByEmpresaIdAndEstado(UUID empresaId, PromotionalCouponStatus estado);

    Optional<PromotionalCoupon> findByIdAndEmpresaId(UUID id, UUID empresaId);

    Optional<PromotionalCoupon> findByEmpresaIdAndCodigoHash(UUID empresaId, String codigoHash);
}

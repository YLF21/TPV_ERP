package com.tpverp.backend.promotion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PromotionalCouponRepository extends JpaRepository<PromotionalCoupon, UUID> {

    List<PromotionalCoupon> findByEmpresaId(UUID empresaId);

    List<PromotionalCoupon> findByEmpresaIdAndEstado(UUID empresaId, PromotionalCouponStatus estado);

    Optional<PromotionalCoupon> findByIdAndEmpresaId(UUID id, UUID empresaId);

    Optional<PromotionalCoupon> findByEmpresaIdAndCodigoHash(UUID empresaId, String codigoHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select coupon
            from PromotionalCoupon coupon
            where coupon.empresaId = :companyId
              and coupon.codigoHash = :codeHash
            """)
    Optional<PromotionalCoupon> findLockedByCompanyIdAndCodeHash(
            @Param("companyId") UUID companyId,
            @Param("codeHash") String codeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select coupon
            from PromotionalCoupon coupon
            where coupon.empresaId = :companyId
              and coupon.id = :couponId
            """)
    Optional<PromotionalCoupon> findLockedByIdAndCompanyId(
            @Param("couponId") UUID couponId,
            @Param("companyId") UUID companyId);
}

package com.tpverp.backend.document;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParkedSaleRecoveryRepository
        extends JpaRepository<ParkedSaleRecovery, UUID> {

    Optional<ParkedSaleRecovery> findByRecoveryIdAndStoreIdAndCompanyId(
            UUID recoveryId, UUID storeId, UUID companyId);

    Optional<ParkedSaleRecovery> findByParkedSaleIdAndStoreIdAndCompanyId(
            UUID parkedSaleId, UUID storeId, UUID companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select recovery from ParkedSaleRecovery recovery
            where recovery.recoveryId = :recoveryId
              and recovery.storeId = :storeId
              and recovery.companyId = :companyId
            """)
    Optional<ParkedSaleRecovery> findLocked(
            @Param("recoveryId") UUID recoveryId,
            @Param("storeId") UUID storeId,
            @Param("companyId") UUID companyId);
}

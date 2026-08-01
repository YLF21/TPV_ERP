package com.tpverp.backend.security.sales;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleOperationSecurityConfigurationRepository
        extends JpaRepository<SaleOperationSecurityConfiguration, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select configuration
            from SaleOperationSecurityConfiguration configuration
            where configuration.storeId = :storeId
            """)
    Optional<SaleOperationSecurityConfiguration> findForUpdate(
            @Param("storeId") UUID storeId);
}

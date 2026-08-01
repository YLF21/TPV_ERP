package com.tpverp.backend.catalog;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InternalEanConfigurationRepository
        extends JpaRepository<InternalEanConfiguration, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select configuration from InternalEanConfiguration configuration where configuration.companyId = :companyId")
    Optional<InternalEanConfiguration> findForUpdate(@Param("companyId") UUID companyId);
}

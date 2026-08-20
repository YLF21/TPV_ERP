package com.tpverp.saas.loyalty;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaasMemberWalletBootstrapRepository
        extends JpaRepository<SaasMemberWalletBootstrap, UUID> {

    Optional<SaasMemberWalletBootstrap> findFirstByCompany_IdOrderByCreatedAtDesc(UUID companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select bootstrap from SaasMemberWalletBootstrap bootstrap where bootstrap.id = :id")
    Optional<SaasMemberWalletBootstrap> findForUpdate(@Param("id") UUID id);
}

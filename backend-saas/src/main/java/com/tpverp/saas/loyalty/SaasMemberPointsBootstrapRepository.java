package com.tpverp.saas.loyalty;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface SaasMemberPointsBootstrapRepository extends JpaRepository<SaasMemberPointsBootstrap, UUID> {
    Optional<SaasMemberPointsBootstrap> findFirstByCompany_IdOrderByCreatedAtDesc(UUID companyId);
    boolean existsByCompany_IdAndStatusNot(UUID companyId, String status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from SaasMemberPointsBootstrap b where b.id = :id")
    Optional<SaasMemberPointsBootstrap> findForUpdate(@Param("id") UUID id);
}

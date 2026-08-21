package com.tpverp.saas.loyalty;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SaasMemberPointsAuthorityRepository extends JpaRepository<SaasMemberPointsAuthority, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from SaasMemberPointsAuthority a where a.companyId = :companyId")
    Optional<SaasMemberPointsAuthority> findForUpdateByCompanyId(@Param("companyId") UUID companyId);
}

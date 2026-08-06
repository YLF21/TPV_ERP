package com.tpverp.backend.document;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TemporaryPriceAuthorizationGrantRepository
        extends JpaRepository<TemporaryPriceAuthorizationGrant, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select grant from TemporaryPriceAuthorizationGrant grant
            where grant.tokenHash = :tokenHash
            """)
    Optional<TemporaryPriceAuthorizationGrant> findForUpdateByTokenHash(
            @Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select grant from TemporaryPriceAuthorizationGrant grant
            where grant.claimSourceType = :sourceType
              and grant.claimSourceId = :sourceId
              and grant.consumedAt is null
            order by grant.id
            """)
    List<TemporaryPriceAuthorizationGrant> findClaimedForUpdate(
            @Param("sourceType") String sourceType,
            @Param("sourceId") UUID sourceId);
}

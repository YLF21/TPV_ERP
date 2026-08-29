package com.tpverp.backend.terminal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PdaPairingGrantRepository extends JpaRepository<PdaPairingGrant, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select grant from PdaPairingGrant grant where grant.codeHash = :codeHash")
    Optional<PdaPairingGrant> findForUpdateByCodeHash(@Param("codeHash") String codeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select grant from PdaPairingGrant grant
            where grant.terminal.id = :terminalId and grant.consumedAt is null
            order by grant.issuedAt
            """)
    List<PdaPairingGrant> findActiveForUpdate(@Param("terminalId") UUID terminalId);
}

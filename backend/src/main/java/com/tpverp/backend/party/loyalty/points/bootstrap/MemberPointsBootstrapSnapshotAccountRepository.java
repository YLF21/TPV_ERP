package com.tpverp.backend.party.loyalty.points.bootstrap;

import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberPointsBootstrapSnapshotAccountRepository
        extends JpaRepository<MemberPointsBootstrapSnapshotAccount, UUID> {
    @Query("""
            select account from MemberPointsBootstrapSnapshotAccount account
            where account.snapshotId = :snapshotId
            order by cast(account.memberId as string)
            """)
    Slice<MemberPointsBootstrapSnapshotAccount> findChunk(
            @Param("snapshotId") UUID snapshotId, Pageable pageable);
}

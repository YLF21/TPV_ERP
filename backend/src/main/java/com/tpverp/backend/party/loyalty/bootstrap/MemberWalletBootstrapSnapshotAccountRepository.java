package com.tpverp.backend.party.loyalty.bootstrap;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberWalletBootstrapSnapshotAccountRepository
        extends JpaRepository<MemberWalletBootstrapSnapshotAccount, UUID> {

    @Query("""
            select account
            from MemberWalletBootstrapSnapshotAccount account
            where account.snapshot.snapshotId = :snapshotId
            order by cast(account.memberId as string)
            """)
    List<MemberWalletBootstrapSnapshotAccount> findBySnapshot_SnapshotIdOrderByMemberId(
            @Param("snapshotId") UUID snapshotId,
            Pageable pageable);
}

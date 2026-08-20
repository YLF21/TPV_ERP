package com.tpverp.saas.loyalty;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaasMemberWalletBootstrapStagingAccountRepository
        extends JpaRepository<SaasMemberWalletBootstrapStagingAccount, UUID> {

    boolean existsBySnapshot_IdAndMemberId(UUID snapshotId, UUID memberId);

    long countBySnapshot_Id(UUID snapshotId);

    List<SaasMemberWalletBootstrapStagingAccount> findBySnapshot_IdOrderByMemberIdAsc(UUID snapshotId);

    @Query("""
            select account
            from SaasMemberWalletBootstrapStagingAccount account
            where account.snapshot.bootstrap.id = :bootstrapId
              and account.snapshot.status = 'COMPLETED'
            order by account.memberId
            """)
    List<SaasMemberWalletBootstrapStagingAccount> findCompletedByBootstrap(
            @Param("bootstrapId") UUID bootstrapId);
}

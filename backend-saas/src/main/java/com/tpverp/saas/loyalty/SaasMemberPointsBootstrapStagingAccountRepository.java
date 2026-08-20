package com.tpverp.saas.loyalty;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface SaasMemberPointsBootstrapStagingAccountRepository extends JpaRepository<SaasMemberPointsBootstrapStagingAccount, UUID> {
    boolean existsBySnapshot_IdAndMemberId(UUID snapshotId, UUID memberId);
    long countBySnapshot_Id(UUID snapshotId);
    @Query("select a from SaasMemberPointsBootstrapStagingAccount a where a.snapshot.bootstrap.id=:id and a.snapshot.status='COMPLETED' order by a.memberId")
    List<SaasMemberPointsBootstrapStagingAccount> findCompletedByBootstrap(@Param("id") UUID bootstrapId);
}

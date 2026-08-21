package com.tpverp.saas.loyalty;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface SaasMemberPointsBootstrapStagingOperationRepository extends JpaRepository<SaasMemberPointsBootstrapStagingOperation, UUID> {
    boolean existsBySnapshot_IdAndKindAndOperationId(UUID snapshotId, String kind, UUID operationId);
    long countBySnapshot_IdAndKind(UUID snapshotId, String kind);
    @Query("select o from SaasMemberPointsBootstrapStagingOperation o where o.snapshot.bootstrap.id=:id and o.snapshot.status='COMPLETED' order by o.operationId")
    List<SaasMemberPointsBootstrapStagingOperation> findCompletedByBootstrap(@Param("id") UUID bootstrapId);
}

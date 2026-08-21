package com.tpverp.saas.loyalty;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasMemberPointsBootstrapSnapshotRepository extends JpaRepository<SaasMemberPointsBootstrapSnapshot, UUID> {
    Optional<SaasMemberPointsBootstrapSnapshot> findByBootstrap_IdAndSnapshotId(UUID bootstrapId, UUID snapshotId);
    Optional<SaasMemberPointsBootstrapSnapshot> findByBootstrap_IdAndStoreId(UUID bootstrapId, UUID storeId);
    List<SaasMemberPointsBootstrapSnapshot> findByBootstrap_IdOrderByStoreIdAsc(UUID bootstrapId);
}

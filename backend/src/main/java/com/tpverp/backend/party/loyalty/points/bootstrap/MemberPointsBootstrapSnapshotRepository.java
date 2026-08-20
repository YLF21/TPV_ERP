package com.tpverp.backend.party.loyalty.points.bootstrap;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberPointsBootstrapSnapshotRepository
        extends JpaRepository<MemberPointsBootstrapSnapshot, UUID> {
    Optional<MemberPointsBootstrapSnapshot> findByBootstrapIdAndStoreId(
            UUID bootstrapId, UUID storeId);
}

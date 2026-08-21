package com.tpverp.backend.party.loyalty.points.bootstrap;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberPointsOfficialSnapshotAccountRepository
        extends JpaRepository<MemberPointsOfficialSnapshotAccount, UUID> {
    List<MemberPointsOfficialSnapshotAccount> findBySnapshotIdOrderByMemberId(
            UUID snapshotId);

    long countBySnapshotId(UUID snapshotId);
}

package com.tpverp.backend.party.loyalty.category;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface MemberCategoryBootstrapUploadRepository
        extends JpaRepository<MemberCategoryBootstrapUpload, UUID> {

    Optional<MemberCategoryBootstrapUpload> findBySnapshotId(UUID snapshotId);

    List<MemberCategoryBootstrapUpload> findTop20ByStatusInAndNextAttemptAtLessThanEqualOrderByUpdatedAtAsc(
            Collection<MemberCategoryBootstrapUpload.Status> statuses,
            Instant now
    );
}

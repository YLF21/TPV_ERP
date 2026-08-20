package com.tpverp.backend.party.loyalty.category;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface MemberCategoryBootstrapSnapshotRepository
        extends JpaRepository<MemberCategoryBootstrapSnapshot, UUID> {
    Optional<MemberCategoryBootstrapSnapshot> findByBootstrapIdAndStoreId(
            UUID bootstrapId, UUID storeId);
}

interface MemberCategoryBootstrapCategoryRepository
        extends JpaRepository<MemberCategoryBootstrapCategory, UUID> {
}

interface MemberCategoryBootstrapAssignmentRepository
        extends JpaRepository<MemberCategoryBootstrapAssignment, UUID> {
}

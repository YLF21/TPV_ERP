package com.tpverp.backend.party.loyalty.bootstrap;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberWalletBootstrapSnapshotRepository
        extends JpaRepository<MemberWalletBootstrapSnapshot, UUID> {

    Optional<MemberWalletBootstrapSnapshot> findByBootstrapIdAndStoreId(
            UUID bootstrapId,
            UUID storeId);

    Optional<MemberWalletBootstrapSnapshot> findFirstByStoreIdAndStatusInOrderByCreatedAtDesc(
            UUID storeId,
            Collection<MemberWalletBootstrapSnapshotStatus> statuses);
}

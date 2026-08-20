package com.tpverp.saas.loyalty;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasMemberWalletBootstrapSnapshotRepository
        extends JpaRepository<SaasMemberWalletBootstrapSnapshot, UUID> {

    Optional<SaasMemberWalletBootstrapSnapshot> findByBootstrap_IdAndSnapshotId(
            UUID bootstrapId,
            UUID snapshotId);

    Optional<SaasMemberWalletBootstrapSnapshot> findByBootstrap_IdAndStoreId(
            UUID bootstrapId,
            UUID storeId);

    List<SaasMemberWalletBootstrapSnapshot> findByBootstrap_IdOrderByStoreIdAsc(UUID bootstrapId);
}

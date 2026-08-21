package com.tpverp.saas.loyalty;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasMemberWalletBootstrapStoreRepository
        extends JpaRepository<SaasMemberWalletBootstrapStore, UUID> {

    List<SaasMemberWalletBootstrapStore> findByBootstrap_IdOrderByStoreIdAsc(UUID bootstrapId);

    Optional<SaasMemberWalletBootstrapStore> findByBootstrap_IdAndStoreId(
            UUID bootstrapId,
            UUID storeId);
}

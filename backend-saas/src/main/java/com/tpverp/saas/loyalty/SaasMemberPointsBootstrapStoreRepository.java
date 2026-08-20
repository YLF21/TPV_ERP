package com.tpverp.saas.loyalty;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasMemberPointsBootstrapStoreRepository extends JpaRepository<SaasMemberPointsBootstrapStore, UUID> {
    List<SaasMemberPointsBootstrapStore> findByBootstrap_IdOrderByStoreIdAsc(UUID bootstrapId);
    Optional<SaasMemberPointsBootstrapStore> findByBootstrap_IdAndStoreId(UUID bootstrapId, UUID storeId);
}

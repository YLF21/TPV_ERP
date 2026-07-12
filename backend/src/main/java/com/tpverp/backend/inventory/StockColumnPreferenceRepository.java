package com.tpverp.backend.inventory;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockColumnPreferenceRepository
        extends JpaRepository<StockColumnPreference, UUID> {

    Optional<StockColumnPreference> findByCompanyIdAndStoreIdAndUserIdAndApp(
            UUID companyId, UUID storeId, UUID userId, String app);
}

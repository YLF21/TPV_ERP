package com.tpverp.backend.terminal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorePaymentConfigurationRepository extends JpaRepository<StorePaymentConfiguration, UUID> {

    Optional<StorePaymentConfiguration> findByStoreId(UUID storeId);
}

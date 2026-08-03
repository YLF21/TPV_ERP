package com.tpverp.backend.document;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreReturnConfigurationRepository
        extends JpaRepository<StoreReturnConfiguration, UUID> {
}

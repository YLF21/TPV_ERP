package com.tpverp.backend.verifactu;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerifactuConfigurationRepository
        extends JpaRepository<VerifactuConfiguration, UUID> {

    Optional<VerifactuConfiguration> findByCompanyId(UUID companyId);
}

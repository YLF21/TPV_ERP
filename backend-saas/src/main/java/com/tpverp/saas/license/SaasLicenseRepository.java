package com.tpverp.saas.license;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasLicenseRepository extends JpaRepository<SaasLicense, UUID> {

    Optional<SaasLicense> findByReference(String reference);
}

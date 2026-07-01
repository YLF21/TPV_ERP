package com.tpverp.saas.license;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasPairingCodeRepository extends JpaRepository<SaasPairingCode, UUID> {

    Optional<SaasPairingCode> findByCode(String code);

    List<SaasPairingCode> findByLicense_ReferenceAndConsumedAtIsNull(String licenseReference);
}

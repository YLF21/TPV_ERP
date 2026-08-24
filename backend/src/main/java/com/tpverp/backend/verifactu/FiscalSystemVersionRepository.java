package com.tpverp.backend.verifactu;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalSystemVersionRepository extends JpaRepository<FiscalSystemVersion, UUID> {

    Optional<FiscalSystemVersion> findByCompanyIdAndInstallationIdAndSystemVersionAndInstallationNumber(
            UUID companyId, UUID installationId, String systemVersion, String installationNumber);
}

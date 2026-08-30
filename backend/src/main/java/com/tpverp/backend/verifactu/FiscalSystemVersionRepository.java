package com.tpverp.backend.verifactu;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalSystemVersionRepository extends JpaRepository<FiscalSystemVersion, UUID> {

    /**
     * Resolves the immutable SIF identity for one exact fiscal release. The
     * public software version is intentionally not enough: several releases
     * may legitimately publish the same version (for example 4.2.0).
     */
    Optional<FiscalSystemVersion>
            findByCompanyIdAndInstallationIdAndSystemVersionAndInstallationNumberAndReleaseId(
                    UUID companyId, UUID installationId, String systemVersion,
                    String installationNumber, String releaseId);

}

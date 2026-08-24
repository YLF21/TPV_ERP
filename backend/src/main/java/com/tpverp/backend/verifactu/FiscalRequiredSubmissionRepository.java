package com.tpverp.backend.verifactu;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalRequiredSubmissionRepository
        extends JpaRepository<FiscalRequiredSubmission, UUID> {
    Optional<FiscalRequiredSubmission> findByCompanyIdAndInstallationIdAndReference(
            UUID companyId, UUID installationId, String reference);

    Optional<FiscalRequiredSubmission> findByIdAndCompanyIdAndInstallationId(
            UUID id, UUID companyId, UUID installationId);
}

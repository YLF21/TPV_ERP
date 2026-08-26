package com.tpverp.backend.verifactu;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface FiscalRequiredSubmissionRepository
        extends JpaRepository<FiscalRequiredSubmission, UUID>,
        FiscalRequiredSubmissionRepositoryCustom {
    Optional<FiscalRequiredSubmission> findByCompanyIdAndInstallationIdAndReference(
            UUID companyId, UUID installationId, String reference);

    Optional<FiscalRequiredSubmission> findByIdAndCompanyIdAndInstallationId(
            UUID id, UUID companyId, UUID installationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FiscalRequiredSubmission> findForUpdateByIdAndCompanyIdAndInstallationId(
            UUID id, UUID companyId, UUID installationId);

}

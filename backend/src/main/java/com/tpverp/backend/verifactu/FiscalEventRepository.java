package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalEventRepository extends JpaRepository<FiscalEvent, UUID> {
    List<FiscalEvent> findTop50ByCompanyIdAndInstallationIdOrderByGeneratedAtDesc(
            UUID companyId, UUID installationId);
    List<FiscalEvent> findAllByCompanyIdAndInstallationIdOrderBySequenceAsc(
            UUID companyId, UUID installationId);
    Optional<FiscalEvent> findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
            UUID companyId, UUID installationId);
}

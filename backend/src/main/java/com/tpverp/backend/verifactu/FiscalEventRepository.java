package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalEventRepository extends JpaRepository<FiscalEvent, UUID> {
    List<FiscalEvent> findTop50ByCompanyIdOrderByGeneratedAtDesc(UUID companyId);
    List<FiscalEvent> findAllByCompanyIdAndInstallationIdOrderBySequenceAsc(
            UUID companyId, UUID installationId);
}

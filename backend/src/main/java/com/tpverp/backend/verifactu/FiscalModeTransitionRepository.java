package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalModeTransitionRepository extends JpaRepository<FiscalModeTransition, UUID> {
    List<FiscalModeTransition> findTop50ByCompanyIdOrderByEffectiveAtDesc(UUID companyId);
}

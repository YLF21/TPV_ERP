package com.tpverp.backend.verifactu;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalSubmissionEvidenceRepository
        extends JpaRepository<FiscalSubmissionEvidence, UUID> {
}

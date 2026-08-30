package com.tpverp.backend.verifactu;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalSubmissionResponseEvidenceRepository
        extends JpaRepository<FiscalSubmissionResponseEvidence, UUID> {

    Optional<FiscalSubmissionResponseEvidence> findByEvidenceId(UUID evidenceId);
}

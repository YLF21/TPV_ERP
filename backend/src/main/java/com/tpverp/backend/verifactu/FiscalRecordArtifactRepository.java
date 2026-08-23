package com.tpverp.backend.verifactu;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalRecordArtifactRepository
        extends JpaRepository<FiscalRecordArtifact, UUID> {

    Optional<FiscalRecordArtifact> findByRecordId(UUID recordId);
}

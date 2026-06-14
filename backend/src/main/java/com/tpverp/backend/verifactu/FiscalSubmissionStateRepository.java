package com.tpverp.backend.verifactu;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalSubmissionStateRepository
        extends JpaRepository<FiscalSubmissionState, UUID> {
}

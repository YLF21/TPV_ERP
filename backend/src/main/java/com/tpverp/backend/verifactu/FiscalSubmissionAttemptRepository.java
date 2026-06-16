package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalSubmissionAttemptRepository
        extends JpaRepository<FiscalSubmissionAttempt, UUID> {

    List<FiscalSubmissionAttempt> findAllByRecordIdOrderByAttemptedAtDesc(UUID recordId);
}

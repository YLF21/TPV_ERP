package com.tpverp.backend.cash;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashReconciliationAttemptRepository
        extends JpaRepository<CashReconciliationAttempt, UUID> {
}

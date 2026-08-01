package com.tpverp.backend.cash;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashReconciliationAttemptRepository
        extends JpaRepository<CashReconciliationAttempt, UUID> {

    Optional<CashReconciliationAttempt>
            findByCloseOperationIdAndIdempotencyKey(UUID closeOperationId, UUID idempotencyKey);

    Optional<CashReconciliationAttempt>
            findFirstByCloseOperationIdOrderByAttemptNumberDesc(UUID closeOperationId);
}

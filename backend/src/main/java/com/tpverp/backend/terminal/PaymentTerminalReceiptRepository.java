package com.tpverp.backend.terminal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTerminalReceiptRepository extends JpaRepository<PaymentTerminalReceiptRecord, UUID> {
    Optional<PaymentTerminalReceiptRecord> findByOperationId(UUID operationId);
}

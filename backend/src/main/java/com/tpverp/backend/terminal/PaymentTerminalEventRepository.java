package com.tpverp.backend.terminal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTerminalEventRepository extends JpaRepository<PaymentTerminalEvent, UUID> {
    List<PaymentTerminalEvent> findByOperationIdOrderByCreatedAtAsc(UUID operationId);
}

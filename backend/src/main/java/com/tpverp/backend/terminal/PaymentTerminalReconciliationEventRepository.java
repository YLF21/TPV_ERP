package com.tpverp.backend.terminal;
import java.util.UUID; import org.springframework.data.jpa.repository.JpaRepository;
public interface PaymentTerminalReconciliationEventRepository extends JpaRepository<PaymentTerminalReconciliationEvent,UUID>{}

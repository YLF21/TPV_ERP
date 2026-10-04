package com.tpverp.backend.terminal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TerminalPhysicalBindingRepository extends JpaRepository<TerminalPhysicalBinding, UUID> {
    Optional<TerminalPhysicalBinding> findByRequestId(UUID requestId);
    List<TerminalPhysicalBinding> findByTerminalTiendaIdOrderByCreatedAt(UUID storeId);
    List<TerminalPhysicalBinding> findByTerminalIdOrderByCreatedAtDesc(UUID terminalId);
}

package com.tpverp.backend.cash;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashSessionRepository extends JpaRepository<CashSession, UUID> {

    Optional<CashSession> findByTerminalIdAndStatus(UUID terminalId, CashSessionStatus status);

    Optional<CashSession> findFirstByTerminalIdAndStatusOrderByClosedAtDesc(
            UUID terminalId, CashSessionStatus status);

    List<CashSession> findAllByTiendaIdAndOpenedAtBetweenOrderByOpenedAtDesc(
            UUID storeId, Instant from, Instant to);
}

package com.tpverp.backend.cash;

import java.util.List;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashMovementRepository extends JpaRepository<CashMovement, UUID> {

    List<CashMovement> findAllBySesionCajaId(UUID sessionId);

    @Query("""
            select movement
            from CashMovement movement
            where movement.terminalId = :terminalId
              and movement.sesionCajaId is null
            order by movement.creadoEn asc
            """)
    List<CashMovement> findAllByTerminalIdAndSesionCajaIsNullOrderByCreadoEnAsc(
            @Param("terminalId") UUID terminalId);

    List<CashMovement> findAllByTiendaIdAndCreadoEnBetweenOrderByCreadoEnAsc(
            UUID storeId, Instant from, Instant to);

    List<CashMovement> findAllByTiendaIdAndTerminalIdAndCreadoEnBetweenOrderByCreadoEnAsc(
            UUID storeId, UUID terminalId, Instant from, Instant to);

    boolean existsByDocumentoPagoId(UUID paymentId);
}

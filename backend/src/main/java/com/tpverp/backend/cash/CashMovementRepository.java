package com.tpverp.backend.cash;

import java.util.List;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashMovementRepository extends JpaRepository<CashMovement, UUID> {

    List<CashMovement> findAllBySesionCajaId(UUID sessionId);

    @Query(value = """
            select movement.*
            from movimiento_caja movement
            where movement.terminal_id = :terminalId
              and movement.sesion_caja_id is null
              and movement.creado_en >= coalesce(
                    (select max(closed.cerrada_en)
                     from sesion_caja closed
                     where closed.terminal_id = :terminalId
                       and closed.estado = 'CERRADA'),
                    '-infinity'::timestamptz)
            order by movement.creado_en asc
            """, nativeQuery = true)
    List<CashMovement> findAllByTerminalIdAndSesionCajaIsNullOrderByCreadoEnAsc(
            @Param("terminalId") UUID terminalId);

    List<CashMovement> findAllByTiendaIdAndCreadoEnBetweenOrderByCreadoEnAsc(
            UUID storeId, Instant from, Instant to);

    @Query("""
            select movement from CashMovement movement
            where movement.tiendaId = :storeId
              and movement.creadoEn >= :from
              and movement.creadoEn < :to
            order by movement.creadoEn asc
            """)
    List<CashMovement> findAllByTiendaIdAndCreadoEnFromInclusiveToExclusiveOrderByCreadoEnAsc(
            @Param("storeId") UUID storeId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    List<CashMovement> findAllByTiendaIdAndTerminalIdAndCreadoEnBetweenOrderByCreadoEnAsc(
            UUID storeId, UUID terminalId, Instant from, Instant to);

    boolean existsByDocumentoPagoId(UUID paymentId);

    boolean existsByDocumentIdAndType(UUID documentId, CashMovementType type);
}

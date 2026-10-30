package com.tpverp.backend.terminal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

public interface TerminalRepository extends JpaRepository<Terminal, UUID> {

    Optional<Terminal> findByTiendaIdAndWorkstationCode(UUID tiendaId, String workstationCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select terminal from Terminal terminal where terminal.tienda.id = :storeId and terminal.workstationCode = :code")
    Optional<Terminal> findWorkstationForUpdate(@Param("storeId") UUID storeId, @Param("code") String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select terminal from Terminal terminal where terminal.tienda.id = :storeId and terminal.tipo = :type")
    Optional<Terminal> findTypeForUpdate(@Param("storeId") UUID storeId, @Param("type") TerminalType type);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select terminal from Terminal terminal where terminal.id = :id")
    Optional<Terminal> findForAuthentication(@Param("id") UUID id);

    Optional<Terminal> findByTiendaIdAndNombreIgnoreCase(UUID tiendaId, String nombre);

    Optional<Terminal> findByTiendaIdAndTipo(UUID tiendaId, TerminalType tipo);

    Optional<Terminal> findByIdAndTiendaId(UUID id, UUID tiendaId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select terminal from Terminal terminal where terminal.id = :id and terminal.tienda.id = :storeId")
    Optional<Terminal> findByIdAndTiendaIdForUpdate(
            @Param("id") UUID id,
            @Param("storeId") UUID storeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select terminal from Terminal terminal where terminal.id = :id and terminal.tienda.id = :storeId")
    Optional<Terminal> findForCashSessionPreparation(
            @Param("id") UUID id,
            @Param("storeId") UUID storeId);

    List<Terminal> findAllByTiendaIdOrderByNombre(UUID tiendaId);

    List<Terminal> findByTiendaIdAndActivaTrue(UUID tiendaId);

    @Modifying(clearAutomatically = false, flushAutomatically = false)
    @Transactional
    @Query(value = """
            update terminal
               set last_seen_at = :now, version = version + 1
             where id = :id and (last_seen_at is null or last_seen_at <= :threshold)
            """, nativeQuery = true)
    int touchLastSeen(@Param("id") UUID id, @Param("now") Instant now,
            @Param("threshold") Instant threshold);
}

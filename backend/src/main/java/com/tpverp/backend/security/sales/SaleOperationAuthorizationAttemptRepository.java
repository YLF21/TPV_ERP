package com.tpverp.backend.security.sales;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleOperationAuthorizationAttemptRepository
        extends JpaRepository<SaleOperationAuthorizationAttempt, UUID> {

    @Query("""
            select attempt
            from SaleOperationAuthorizationAttempt attempt
            where attempt.storeId = :storeId
              and attempt.operatorId = :operatorId
              and attempt.terminalId = :terminalId
              and attempt.operationCode = :operationCode
            """)
    Optional<SaleOperationAuthorizationAttempt> findByScope(
            @Param("storeId") UUID storeId,
            @Param("operatorId") UUID operatorId,
            @Param("terminalId") UUID terminalId,
            @Param("operationCode") SaleOperationCode operationCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select attempt
            from SaleOperationAuthorizationAttempt attempt
            where attempt.storeId = :storeId
              and attempt.operatorId = :operatorId
              and attempt.terminalId = :terminalId
              and attempt.operationCode = :operationCode
            """)
    Optional<SaleOperationAuthorizationAttempt> findByScopeForUpdate(
            @Param("storeId") UUID storeId,
            @Param("operatorId") UUID operatorId,
            @Param("terminalId") UUID terminalId,
            @Param("operationCode") SaleOperationCode operationCode);

    @Modifying
    @Query(value = """
            insert into intento_autorizacion_operacion_venta (
                id,
                tienda_id,
                operador_id,
                terminal_id,
                codigo_operacion,
                fallos_consecutivos,
                bloqueado_hasta,
                reserva_id,
                reserva_hasta,
                ultimo_fallo_en,
                actualizada_en,
                row_version
            ) values (
                :id,
                :storeId,
                :operatorId,
                :terminalId,
                :operationCode,
                0,
                null,
                null,
                null,
                null,
                :now,
                0
            )
            on conflict (
                tienda_id,
                operador_id,
                terminal_id,
                codigo_operacion
            ) do nothing
            """, nativeQuery = true)
    int ensureExists(
            @Param("id") UUID id,
            @Param("storeId") UUID storeId,
            @Param("operatorId") UUID operatorId,
            @Param("terminalId") UUID terminalId,
            @Param("operationCode") String operationCode,
            @Param("now") Instant now);
}

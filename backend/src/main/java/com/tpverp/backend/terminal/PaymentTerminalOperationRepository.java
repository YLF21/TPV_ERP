package com.tpverp.backend.terminal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface PaymentTerminalOperationRepository extends JpaRepository<PaymentTerminalOperation, UUID> {
    @Query(value="select 1 from (select pg_advisory_xact_lock(hashtextextended(:lockKey,0))) locked",nativeQuery=true)
    Integer lockIdempotencyKey(@Param("lockKey") String lockKey);
    Optional<PaymentTerminalOperation> findByTerminalIdAndIdempotencyKey(UUID terminalId, String idempotencyKey);
    Optional<PaymentTerminalOperation> findByDocumentPaymentId(UUID documentPaymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select operation from PaymentTerminalOperation operation where operation.id = :id")
    Optional<PaymentTerminalOperation> findLockedById(@Param("id") UUID id);

    @Query("""
            select coalesce(sum(operation.amount), 0)
            from PaymentTerminalOperation operation
            where operation.originalOperationId = :originalId
              and operation.operationType = com.tpverp.backend.terminal.PaymentTerminalOperationType.REFUND
              and operation.status in (
                com.tpverp.backend.terminal.PaymentTerminalOperationStatus.PENDING,
                com.tpverp.backend.terminal.PaymentTerminalOperationStatus.SENT,
                com.tpverp.backend.terminal.PaymentTerminalOperationStatus.TIMEOUT,
                com.tpverp.backend.terminal.PaymentTerminalOperationStatus.REVIEW_REQUIRED)
            """)
    java.math.BigDecimal reservedRefundAmount(@Param("originalId") UUID originalId);

    @Query("""
            select count(operation) from PaymentTerminalOperation operation
            where operation.originalOperationId = :originalId
              and operation.operationType = com.tpverp.backend.terminal.PaymentTerminalOperationType.VOID
              and operation.status in (
                com.tpverp.backend.terminal.PaymentTerminalOperationStatus.PENDING,
                com.tpverp.backend.terminal.PaymentTerminalOperationStatus.SENT,
                com.tpverp.backend.terminal.PaymentTerminalOperationStatus.TIMEOUT,
                com.tpverp.backend.terminal.PaymentTerminalOperationStatus.APPROVED,
                com.tpverp.backend.terminal.PaymentTerminalOperationStatus.REVIEW_REQUIRED)
            """)
    long activeVoidCount(@Param("originalId") UUID originalId);

    @Query(value="""
            select coalesce(sum(amount - refunded_amount), 0)
              from payment_terminal_operation
             where terminal_id = :terminalId and store_id = :storeId and provider = :provider
               and operation_type = 'CHARGE'
               and status in ('APPROVED','PARTIALLY_REFUNDED','REFUNDED')
               and created_at >= :from and created_at < :until
            """,nativeQuery=true)
    java.math.BigDecimal reconciliationTotal(@Param("terminalId") UUID terminalId,@Param("storeId") UUID storeId,
            @Param("provider") String provider,@Param("from") java.time.Instant from,@Param("until") java.time.Instant until);

    @Query("""
            select operation from PaymentTerminalOperation operation
            where operation.status in (
                com.tpverp.backend.terminal.PaymentTerminalOperationStatus.PENDING,
                com.tpverp.backend.terminal.PaymentTerminalOperationStatus.SENT,
                com.tpverp.backend.terminal.PaymentTerminalOperationStatus.TIMEOUT)
              and (operation.nextRetryAt is null or operation.nextRetryAt <= :now)
              and (operation.status <> com.tpverp.backend.terminal.PaymentTerminalOperationStatus.SENT or operation.updatedAt <= :sentBefore)
            order by operation.createdAt
            """)
    java.util.List<PaymentTerminalOperation> findRecoverable(@Param("now") java.time.Instant now,@Param("sentBefore") java.time.Instant sentBefore,
            org.springframework.data.domain.Pageable pageable);

    @Query(value="""
            select operation.* from payment_terminal_operation operation
            where operation.operation_type in ('CHARGE','REFUND')
              and operation.status = 'APPROVED'
              and operation.document_id is null
              and (operation.next_retry_at is null or operation.next_retry_at <= :now)
              and (operation.processing_lease_until is null or operation.processing_lease_until <= :now)
              and not exists (
                  select 1 from sale_payment_allocation allocation
                  where allocation.operation_id = operation.id
              )
            order by operation.updated_at
            """,nativeQuery=true)
    java.util.List<PaymentTerminalOperation> findApprovedWithoutDocument(@Param("now") java.time.Instant now,
            org.springframework.data.domain.Pageable pageable);
}

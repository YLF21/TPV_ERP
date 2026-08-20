package com.tpverp.backend.party;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberPointsOperationRepository
        extends JpaRepository<MemberPointsOperation, UUID> {
    Optional<MemberPointsOperation> findByOperationTypeAndSourceDocumentIdAndSourceCheckpoint(
            MemberPointsOperationType operationType,
            UUID sourceDocumentId,
            String sourceCheckpoint);

    Optional<MemberPointsOperation> findByOperationTypeAndSourceDocumentId(
            MemberPointsOperationType operationType,
            UUID sourceDocumentId);

    Optional<MemberPointsOperation> findByOperationTypeAndOriginalDocumentId(
            MemberPointsOperationType operationType,
            UUID originalDocumentId);

    @Modifying
    @Query(value = """
            insert into member_points_operation (
                operation_id, miembro_id, empresa_id, tienda_id, store_sequence,
                operation_type, amount, source_document_id, original_document_id,
                occurred_at, local_points_delta, local_debt_delta,
                source_checkpoint, payload_hash
            ) values (
                :operationId, :memberId, :companyId, :storeId, :storeSequence,
                :operationType, :amount, :sourceDocumentId, :originalDocumentId,
                :occurredAt, :localPointsDelta, :localDebtDelta,
                :sourceCheckpoint, :payloadHash
            ) on conflict (operation_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("operationId") UUID operationId,
            @Param("memberId") UUID memberId,
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId,
            @Param("storeSequence") long storeSequence,
            @Param("operationType") String operationType,
            @Param("amount") long amount,
            @Param("sourceDocumentId") UUID sourceDocumentId,
            @Param("originalDocumentId") UUID originalDocumentId,
            @Param("occurredAt") Instant occurredAt,
            @Param("localPointsDelta") long localPointsDelta,
            @Param("localDebtDelta") long localDebtDelta,
            @Param("sourceCheckpoint") String sourceCheckpoint,
            @Param("payloadHash") String payloadHash);

    @Query("""
            select operation.operationId as operationId,
                   operation.member.id as memberId,
                   operation.operationType as operationType,
                   operation.amount as amount,
                   operation.sourceDocumentId as sourceDocumentId,
                   operation.originalDocumentId as originalDocumentId,
                   operation.occurredAt as occurredAt,
                   operation.localPointsDelta as localPointsDelta,
                   operation.localDebtDelta as localDebtDelta,
                   operation.storeSequence as storeSequence
            from MemberPointsOperation operation
            where operation.companyId = :companyId
              and operation.storeId = :storeId
              and operation.storeSequence <= :cutSequence
            order by cast(operation.operationId as string)
            """)
    Slice<MemberPointsBootstrapOperationProjection> findBootstrapOperations(
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId,
            @Param("cutSequence") long cutSequence,
            Pageable pageable);

    @Query("""
            select operation.operationId as operationId,
                   operation.member.id as memberId,
                   operation.operationType as operationType,
                   operation.amount as amount,
                   operation.sourceDocumentId as sourceDocumentId,
                   operation.originalDocumentId as originalDocumentId,
                   operation.occurredAt as occurredAt,
                   operation.localPointsDelta as localPointsDelta,
                   operation.localDebtDelta as localDebtDelta,
                   operation.storeSequence as storeSequence
            from MemberPointsOperation operation
            where operation.companyId = :companyId
              and operation.storeId = :storeId
              and operation.storeSequence > :afterSequence
              and operation.storeSequence <= :throughSequence
            order by cast(operation.operationId as string)
            """)
    Slice<MemberPointsBootstrapOperationProjection> findBootstrapOperationsRange(
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId,
            @Param("afterSequence") long afterSequence,
            @Param("throughSequence") long throughSequence,
            Pageable pageable);

    interface MemberPointsBootstrapOperationProjection {
        UUID getOperationId();
        UUID getMemberId();
        MemberPointsOperationType getOperationType();
        long getAmount();
        UUID getSourceDocumentId();
        UUID getOriginalDocumentId();
        Instant getOccurredAt();
        long getLocalPointsDelta();
        long getLocalDebtDelta();
        long getStoreSequence();
    }
}

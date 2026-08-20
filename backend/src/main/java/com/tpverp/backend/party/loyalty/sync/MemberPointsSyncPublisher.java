package com.tpverp.backend.party.loyalty.sync;

import com.fasterxml.jackson.databind.node.NullNode;
import com.tpverp.backend.party.MemberPointsOperation;
import com.tpverp.backend.party.MemberPointsOperationRepository;
import com.tpverp.backend.party.MemberPointsOperationType;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberPointsSyncPublisher {

    private static final String ENTITY_TYPE = "MEMBER_POINTS_OPERATION";

    private final MemberPointsOperationRepository operations;
    private final SyncOutboxService syncOutbox;

    public MemberPointsSyncPublisher(
            MemberPointsOperationRepository operations,
            SyncOutboxService syncOutbox) {
        this.operations = operations;
        this.syncOutbox = syncOutbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publishCreated(MemberPointsOperation operation) {
        Objects.requireNonNull(operation, "operation");
        var sameAction = findSameAction(operation);
        if (sameAction.isPresent()) {
            assertSameContent(sameAction.get(), operation);
            return;
        }

        var inserted = operations.insertIfAbsent(
                operation.getOperationId(),
                operation.getMember().getId(),
                operation.getCompanyId(),
                operation.getStoreId(),
                operation.getStoreSequence(),
                operation.getOperationType().name(),
                operation.getAmount(),
                operation.getSourceDocumentId(),
                operation.getOriginalDocumentId(),
                operation.getOccurredAt(),
                operation.getLocalPointsDelta(),
                operation.getLocalDebtDelta(),
                operation.getSourceCheckpoint(),
                operation.getPayloadHash());
        if (inserted == 0) {
            var existing = operations.findById(operation.getOperationId())
                    .orElseThrow(() -> new IllegalStateException(
                            "No se pudo resolver la operacion de puntos existente"));
            assertSameContent(existing, operation);
            return;
        }

        var payload = new LinkedHashMap<String, Object>();
        payload.put("schemaVersion", 1);
        payload.put("operationId", operation.getOperationId());
        payload.put("memberId", operation.getMember().getId());
        payload.put("operationType", operation.getOperationType().name());
        payload.put("amount", operation.getAmount());
        payload.put("sourceDocumentId", nullable(operation.getSourceDocumentId()));
        payload.put("originalDocumentId", nullable(operation.getOriginalDocumentId()));
        payload.put("occurredAt", operation.getOccurredAt());
        payload.put("localPointsDelta", operation.getLocalPointsDelta());
        payload.put("localDebtDelta", operation.getLocalDebtDelta());

        syncOutbox.enqueue(new SyncOutboundEventCommand(
                operation.getCompanyId(),
                operation.getStoreId(),
                null,
                operation.getStoreSequence(),
                ENTITY_TYPE,
                operation.getOperationId(),
                SyncOperation.CREAR,
                payload));
    }

    private Optional<MemberPointsOperation> findSameAction(
            MemberPointsOperation operation) {
        return switch (operation.getOperationType()) {
            case SALE_EARN -> operations
                    .findByOperationTypeAndSourceDocumentIdAndSourceCheckpoint(
                            operation.getOperationType(),
                            operation.getSourceDocumentId(),
                            operation.getSourceCheckpoint());
            case RETURN_REVERSAL, RETURN_CANCELLATION -> operations
                    .findByOperationTypeAndSourceDocumentId(
                            operation.getOperationType(),
                            operation.getSourceDocumentId());
            case SALE_CANCELLATION -> operations
                    .findByOperationTypeAndOriginalDocumentId(
                            operation.getOperationType(),
                            operation.getOriginalDocumentId());
            case MANUAL_ADJUSTMENT -> Optional.empty();
        };
    }

    private static void assertSameContent(
            MemberPointsOperation existing,
            MemberPointsOperation requested) {
        if (existing.getOperationId().equals(requested.getOperationId())
                && existing.getPayloadHash().equals(requested.getPayloadHash())) {
            return;
        }
        throw new IllegalStateException("Conflicto de operacion incremental de puntos");
    }

    private static Object nullable(Object value) {
        return value == null ? NullNode.getInstance() : value;
    }
}

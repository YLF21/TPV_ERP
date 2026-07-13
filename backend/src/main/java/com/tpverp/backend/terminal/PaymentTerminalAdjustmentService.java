package com.tpverp.backend.terminal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentTerminalAdjustmentService {
    private final PaymentTerminalOperationRepository repository;

    public PaymentTerminalAdjustmentService(PaymentTerminalOperationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public PaymentTerminalOperation reserveRefund(UUID id, UUID originalId, UUID terminalId, UUID storeId,
            PaymentTerminalProvider provider, String idempotencyKey, String requestHash, BigDecimal amount,
            String configurationHash, long configurationVersion, Instant now) {
        repository.lockIdempotencyKey(terminalId+":"+idempotencyKey);
        var replay = repository.findByTerminalIdAndIdempotencyKey(terminalId, idempotencyKey);
        if (replay.isPresent()) return compatibleReplay(replay.orElseThrow(), PaymentTerminalOperationType.REFUND,
                originalId, requestHash, amount);
        var original = lockedCompatibleCharge(originalId, terminalId, storeId, provider);
        if (repository.activeVoidCount(originalId) != 0) {
            throw new IllegalStateException("El cobro tiene una anulacion activa");
        }
        var reserved = repository.reservedRefundAmount(originalId);
        if (reserved == null) reserved = BigDecimal.ZERO;
        var remaining = original.getAmount().subtract(original.getRefundedAmount()).subtract(reserved);
        if (amount == null || amount.signum() <= 0 || amount.compareTo(remaining) > 0) {
            throw new IllegalArgumentException("La devolucion supera el saldo reembolsable");
        }
        return repository.saveAndFlush(PaymentTerminalOperation.reserve(id, terminalId, storeId, provider,
                original.getMode(), PaymentTerminalOperationType.REFUND, originalId, idempotencyKey,
                requestHash, amount, configurationHash, configurationVersion, now));
    }

    @Transactional
    public PaymentTerminalOperation reserveVoid(UUID id, UUID originalId, UUID terminalId, UUID storeId,
            PaymentTerminalProvider provider, String idempotencyKey, String requestHash,
            String configurationHash, long configurationVersion, Instant now) {
        repository.lockIdempotencyKey(terminalId+":"+idempotencyKey);
        var replay = repository.findByTerminalIdAndIdempotencyKey(terminalId, idempotencyKey);
        if (replay.isPresent()) return compatibleReplay(replay.orElseThrow(), PaymentTerminalOperationType.VOID,
                originalId, requestHash, null);
        var original = lockedCompatibleCharge(originalId, terminalId, storeId, provider);
        if (repository.activeVoidCount(originalId) != 0) {
            throw new IllegalStateException("El cobro ya tiene una anulacion activa");
        }
        var reservedRefunds = repository.reservedRefundAmount(originalId);
        if (reservedRefunds != null && reservedRefunds.signum() != 0) {
            throw new IllegalStateException("El cobro tiene una devolucion activa");
        }
        if (original.getRefundedAmount().signum() != 0) {
            throw new IllegalArgumentException("Un cobro parcialmente devuelto no admite void");
        }
        if (original.getDocumentId() != null) {
            throw new IllegalStateException("Un cobro ya liquidado y documentado no admite void");
        }
        return repository.saveAndFlush(PaymentTerminalOperation.reserve(id, terminalId, storeId, provider,
                original.getMode(), PaymentTerminalOperationType.VOID, originalId, idempotencyKey,
                requestHash, original.getAmount(), configurationHash, configurationVersion, now));
    }

    @Transactional
    public PaymentTerminalOperation markSent(UUID id, Instant now) {
        var operation=repository.findLockedById(id).orElseThrow();
        if(operation.getStatus()==PaymentTerminalOperationStatus.PENDING){ operation.markSent("GATEWAY_SEND",now); repository.save(operation); }
        return operation;
    }

    @Transactional
    public PaymentTerminalOperation complete(UUID id, PaymentTerminalResult result, Instant now) {
        var operation=repository.findLockedById(id).orElseThrow();
        if(operation.getStatus()!=PaymentTerminalOperationStatus.SENT) return operation;
        switch(result.status()) {
            case CANCELLED -> { if(operation.getOperationType()==PaymentTerminalOperationType.VOID)
                    operation.voidApproved(result.reference(),result.authorization(),now);
                else operation.decline("PAYMENT_REFUND_CANCELLED",result.message(),now); }
            case APPROVED,REFUNDED,PARTIALLY_REFUNDED -> {
                if(operation.getOperationType()==PaymentTerminalOperationType.VOID) operation.voidApproved(result.reference(),result.authorization(),now);
                else operation.approve(result.reference(),result.authorization(),now);
            }
            case DECLINED -> operation.decline(result.code(),result.message(),now);
            case TIMEOUT,PENDING -> operation.timeout(result.code(),result.message(),now);
            case ERROR -> operation.fail(result.code(),result.message(),now);
            case REVIEW_REQUIRED -> operation.markReviewRequired(result.code(),result.message(),now);
            default -> operation.fail("PAYMENT_RESULT_INVALID","Estado inesperado del proveedor",now);
        }
        if(operation.getOperationType()==PaymentTerminalOperationType.REFUND
                && (result.status()==PaymentTerminalOperationStatus.APPROVED
                    || result.status()==PaymentTerminalOperationStatus.REFUNDED
                    || result.status()==PaymentTerminalOperationStatus.PARTIALLY_REFUNDED)) {
            var original=repository.findLockedById(operation.getOriginalOperationId()).orElseThrow();
            original.recordRefund(operation.getAmount(),now); repository.save(original);
        }
        if(operation.getOperationType()==PaymentTerminalOperationType.VOID
                && (result.status()==PaymentTerminalOperationStatus.APPROVED
                    || result.status()==PaymentTerminalOperationStatus.CANCELLED)) {
            var original=repository.findLockedById(operation.getOriginalOperationId()).orElseThrow();
            original.recordVoid(now); repository.save(original);
        }
        return repository.save(operation);
    }

    private static PaymentTerminalOperation compatibleReplay(PaymentTerminalOperation operation,
            PaymentTerminalOperationType type, UUID originalId, String requestHash, BigDecimal amount) {
        if (operation.getOperationType() != type || !operation.getOriginalOperationId().equals(originalId)
                || !operation.getRequestHash().equals(requestHash)
                || (amount != null && operation.getAmount().compareTo(amount) != 0)) {
            throw new IllegalStateException("La clave idempotente pertenece a otra operacion");
        }
        return operation;
    }

    private PaymentTerminalOperation lockedCompatibleCharge(UUID originalId, UUID terminalId, UUID storeId,
            PaymentTerminalProvider provider) {
        var original = repository.findLockedById(Objects.requireNonNull(originalId, "originalId"))
                .orElseThrow(() -> new IllegalArgumentException("Operacion original no encontrada"));
        if (original.getOperationType() != PaymentTerminalOperationType.CHARGE
                || (original.getStatus() != PaymentTerminalOperationStatus.APPROVED
                    && original.getStatus() != PaymentTerminalOperationStatus.PARTIALLY_REFUNDED)) {
            throw new IllegalArgumentException("La operacion original no es un cobro aprobado");
        }
        if (!original.getTerminalId().equals(terminalId) || !original.getStoreId().equals(storeId)
                || original.getProvider() != provider || !"EUR".equals(original.getCurrency())) {
            throw new IllegalArgumentException("La operacion original no pertenece al mismo contexto de pago");
        }
        return original;
    }
}

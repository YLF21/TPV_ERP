package com.tpverp.backend.document;

import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalOperation;
import com.tpverp.backend.terminal.PaymentTerminalOperationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "customer_receivable_payment_reservation")
public class CustomerReceivablePaymentReservation {

    public enum Kind { STANDARD, INTEGRATED_CARD }
    public enum Status { RESERVED, DISPATCHING, APPROVED, COMPLETED, RELEASED }

    private static final Set<Status> ACTIVE = Set.of(
            Status.RESERVED, Status.DISPATCHING, Status.APPROVED);

    @Id
    private UUID id;
    @Column(name = "document_id", nullable = false)
    private UUID documentId;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;
    @Column(name = "terminal_id", nullable = false)
    private UUID terminalId;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Kind kind;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status;
    @Column(name = "lease_owner")
    private UUID leaseOwner;
    @Column(name = "lease_until")
    private Instant leaseUntil;
    @Column(name = "document_payment_id", unique = true)
    private UUID documentPaymentId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Version
    private long version;

    protected CustomerReceivablePaymentReservation() {}

    public static CustomerReceivablePaymentReservation reserve(
            UUID id, UUID documentId, UUID storeId, UUID terminalId, UUID userId,
            String requestHash, BigDecimal amount, Kind kind,
            UUID owner, Instant leaseUntil, Instant now) {
        if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be a lowercase SHA-256 hash");
        }
        var value = new CustomerReceivablePaymentReservation();
        value.id = Objects.requireNonNull(id, "id");
        value.documentId = Objects.requireNonNull(documentId, "documentId");
        value.storeId = Objects.requireNonNull(storeId, "storeId");
        value.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        value.userId = Objects.requireNonNull(userId, "userId");
        value.requestHash = requestHash;
        value.amount = positive(amount);
        value.kind = Objects.requireNonNull(kind, "kind");
        value.status = Status.RESERVED;
        value.leaseOwner = Objects.requireNonNull(owner, "owner");
        value.leaseUntil = future(leaseUntil, now);
        value.createdAt = Objects.requireNonNull(now, "now");
        value.updatedAt = now;
        return value;
    }

    public boolean claim(UUID owner, Instant until, Instant now) {
        Objects.requireNonNull(owner, "owner");
        future(until, now);
        if (!ACTIVE.contains(status)) return false;
        if (leaseOwner != null && leaseUntil != null && leaseUntil.isAfter(now)) return false;
        leaseOwner = owner;
        leaseUntil = until;
        updatedAt = now;
        return true;
    }

    public void reactivate(UUID owner, Instant until, Instant now) {
        if (status != Status.RELEASED) {
            throw new IllegalStateException("receivable_payment_not_released");
        }
        leaseOwner = Objects.requireNonNull(owner, "owner");
        leaseUntil = future(until, now);
        status = Status.RESERVED;
        updatedAt = now;
    }

    public void markDispatching(UUID owner, Instant now) {
        requireOwner(owner);
        if (kind != Kind.INTEGRATED_CARD || status != Status.RESERVED) {
            throw new IllegalStateException("receivable_payment_not_dispatchable");
        }
        status = Status.DISPATCHING;
        updatedAt = now;
    }

    public void recordTerminalResult(PaymentTerminalOperationStatus result, Instant now) {
        recordTerminalResult(result, result != PaymentTerminalOperationStatus.ERROR, now);
    }

    public void recordTerminalResult(
            PaymentTerminalOperationStatus result, boolean finalOutcome, Instant now) {
        if (kind != Kind.INTEGRATED_CARD || status != Status.DISPATCHING) {
            throw new IllegalStateException("receivable_payment_not_dispatching");
        }
        if (result == PaymentTerminalOperationStatus.APPROVED) {
            status = Status.APPROVED;
            leaseOwner = null;
            leaseUntil = null;
        } else if (result == PaymentTerminalOperationStatus.DECLINED
                || result == PaymentTerminalOperationStatus.CANCELLED
                || (result == PaymentTerminalOperationStatus.ERROR && finalOutcome)) {
            release(now);
            return;
        }
        updatedAt = now;
    }

    public void complete(UUID paymentId, Instant now) {
        if (status != Status.RESERVED && status != Status.APPROVED) {
            throw new IllegalStateException("receivable_payment_not_completable");
        }
        documentPaymentId = Objects.requireNonNull(paymentId, "paymentId");
        status = Status.COMPLETED;
        leaseOwner = null;
        leaseUntil = null;
        completedAt = Objects.requireNonNull(now, "now");
        updatedAt = now;
    }

    public void release(Instant now) {
        if (status == Status.COMPLETED) return;
        status = Status.RELEASED;
        leaseOwner = null;
        leaseUntil = null;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    boolean sameIdentity(UUID documentId, UUID storeId, UUID terminalId, UUID userId,
            String requestHash, BigDecimal amount, Kind kind) {
        return this.documentId.equals(documentId) && this.storeId.equals(storeId)
                && this.terminalId.equals(terminalId) && this.userId.equals(userId)
                && this.requestHash.equals(requestHash)
                && this.amount.compareTo(Money.euros(amount)) == 0 && this.kind == kind;
    }

    boolean matchesRecoveryScope(UUID documentId, UUID storeId, UUID terminalId) {
        return this.documentId.equals(documentId) && this.storeId.equals(storeId)
                && this.terminalId.equals(terminalId) && kind == Kind.INTEGRATED_CARD;
    }

    boolean matchesOperation(PaymentTerminalOperation operation) {
        return id.equals(operation.getId())
                && operation.getOperationType() == PaymentTerminalOperationType.CHARGE
                && terminalId.equals(operation.getTerminalId())
                && storeId.equals(operation.getStoreId())
                && requestHash.equals(operation.getRequestHash())
                && amount.compareTo(operation.getAmount()) == 0
                && (operation.getDocumentId() == null
                    || documentId.equals(operation.getDocumentId()));
    }

    boolean hasExpiredReservedLease(Instant now) {
        return status == Status.RESERVED && (leaseUntil == null || !leaseUntil.isAfter(now));
    }

    boolean reservesBalance() { return ACTIVE.contains(status); }
    boolean isOwnedBy(UUID owner) { return Objects.equals(leaseOwner, owner); }
    UUID getId() { return id; }
    UUID getDocumentId() { return documentId; }
    BigDecimal getAmount() { return amount; }
    Kind getKind() { return kind; }
    Status getStatus() { return status; }
    UUID getLeaseOwner() { return leaseOwner; }

    private void requireOwner(UUID owner) {
        if (!Objects.equals(leaseOwner, owner)) {
            throw new IllegalStateException("receivable_payment_lease_lost");
        }
    }

    private static BigDecimal positive(BigDecimal amount) {
        var value = Money.euros(amount);
        if (value.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        return value;
    }

    private static Instant future(Instant until, Instant now) {
        Objects.requireNonNull(until, "leaseUntil");
        Objects.requireNonNull(now, "now");
        if (!until.isAfter(now)) throw new IllegalArgumentException("lease must end in the future");
        return until;
    }
}

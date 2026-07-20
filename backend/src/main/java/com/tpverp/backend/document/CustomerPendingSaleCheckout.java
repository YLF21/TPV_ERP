package com.tpverp.backend.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "customer_pending_sale_checkout", uniqueConstraints = @UniqueConstraint(
        columnNames = {"terminal_id", "checkout_id"}))
public class CustomerPendingSaleCheckout {

    @Id
    private UUID id;
    @Column(name = "checkout_id", nullable = false)
    private UUID checkoutId;
    @Column(name = "terminal_id", nullable = false)
    private UUID terminalId;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;
    @Column(name = "document_id", unique = true)
    private UUID documentId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "processing_owner", nullable = false)
    private UUID processingOwner;
    @Column(name = "processing_lease_until", nullable = false)
    private Instant processingLeaseUntil;
    @Version
    private long version;

    protected CustomerPendingSaleCheckout() {
    }

    public static CustomerPendingSaleCheckout reserve(
            UUID id,
            UUID checkoutId,
            UUID terminalId,
            UUID storeId,
            UUID userId,
            String requestHash,
            Instant createdAt) {
        return reserve(id, checkoutId, terminalId, storeId, userId, requestHash,
                UUID.randomUUID(), createdAt.plusSeconds(30), createdAt);
    }

    public static CustomerPendingSaleCheckout reserve(
            UUID id,
            UUID checkoutId,
            UUID terminalId,
            UUID storeId,
            UUID userId,
            String requestHash,
            UUID processingOwner,
            Instant processingLeaseUntil,
            Instant createdAt) {
        if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be a lowercase SHA-256 hash");
        }
        var checkout = new CustomerPendingSaleCheckout();
        checkout.id = Objects.requireNonNull(id, "id");
        checkout.checkoutId = Objects.requireNonNull(checkoutId, "checkoutId");
        checkout.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        checkout.storeId = Objects.requireNonNull(storeId, "storeId");
        checkout.userId = Objects.requireNonNull(userId, "userId");
        checkout.requestHash = requestHash;
        checkout.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        checkout.processingOwner = Objects.requireNonNull(processingOwner, "processingOwner");
        checkout.processingLeaseUntil = Objects.requireNonNull(
                processingLeaseUntil, "processingLeaseUntil");
        if (!processingLeaseUntil.isAfter(createdAt)) {
            throw new IllegalArgumentException("processing lease must end after creation");
        }
        return checkout;
    }

    public boolean claim(UUID owner, Instant leaseUntil, Instant now) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        Objects.requireNonNull(now, "now");
        if (!leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("processing lease must end in the future");
        }
        if (completedAt != null) return false;
        if (processingLeaseUntil != null && processingLeaseUntil.isAfter(now)) return false;
        processingOwner = owner;
        processingLeaseUntil = leaseUntil;
        return true;
    }

    boolean isOwnedBy(UUID owner) {
        return processingOwner.equals(owner);
    }

    public void complete(UUID documentId, Instant completedAt) {
        if (this.completedAt != null) {
            throw new IllegalStateException("pending_sale_checkout_already_completed");
        }
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
    }

    public boolean matchesHash(String hash) {
        return requestHash.equals(hash);
    }

    boolean matchesScope(UUID storeId, UUID userId) {
        return this.storeId.equals(storeId) && this.userId.equals(userId);
    }

    boolean isCompleted() {
        return completedAt != null;
    }

    UUID getDocumentId() {
        return documentId;
    }

    UUID getId() {
        return id;
    }

    Instant getCompletedAt() {
        return completedAt;
    }
}

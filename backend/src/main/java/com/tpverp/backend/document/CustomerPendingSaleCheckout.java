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
        return checkout;
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

    Instant getCompletedAt() {
        return completedAt;
    }
}

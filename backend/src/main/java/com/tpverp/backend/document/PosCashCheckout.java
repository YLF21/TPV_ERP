package com.tpverp.backend.document;

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
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "pos_cash_checkout")
public class PosCashCheckout {
    enum Status { PENDING, COMPLETED }

    @Id private UUID id;
    @Column(name = "checkout_id", nullable = false) private UUID checkoutId;
    @Column(name = "company_id", nullable = false) private UUID companyId;
    @Column(name = "store_id", nullable = false) private UUID storeId;
    @Column(name = "terminal_id", nullable = false) private UUID terminalId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Status status;
    @Column(name = "documento_id") private UUID documentId;
    @Column(name = "ticket_number", length = 32) private String ticketNumber;
    @Column(precision = 19, scale = 2) private BigDecimal total;
    @Column(precision = 19, scale = 2) private BigDecimal received;
    @Column(name = "change_amount", precision = 19, scale = 2) private BigDecimal change;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ticket_snapshot", columnDefinition = "jsonb")
    private String ticketSnapshot;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;

    protected PosCashCheckout() {}

    static PosCashCheckout reserve(
            UUID id, UUID checkoutId, UUID companyId, UUID storeId, UUID terminalId,
            UUID userId, String requestHash, Instant now) {
        var checkout = new PosCashCheckout();
        checkout.id = Objects.requireNonNull(id);
        checkout.checkoutId = Objects.requireNonNull(checkoutId);
        checkout.companyId = Objects.requireNonNull(companyId);
        checkout.storeId = Objects.requireNonNull(storeId);
        checkout.terminalId = Objects.requireNonNull(terminalId);
        checkout.userId = Objects.requireNonNull(userId);
        checkout.requestHash = Objects.requireNonNull(requestHash);
        checkout.status = Status.PENDING;
        checkout.createdAt = Objects.requireNonNull(now);
        checkout.updatedAt = now;
        return checkout;
    }

    void complete(
            UUID documentId, String ticketNumber, BigDecimal total, BigDecimal received,
            BigDecimal change, String ticketSnapshot, Instant now) {
        if (status != Status.PENDING) throw new IllegalStateException("cash_checkout_already_completed");
        this.documentId = Objects.requireNonNull(documentId);
        this.ticketNumber = Objects.requireNonNull(ticketNumber);
        this.total = Money.euros(total);
        this.received = Money.euros(received);
        this.change = Money.euros(change);
        this.ticketSnapshot = Objects.requireNonNull(ticketSnapshot);
        this.status = Status.COMPLETED;
        this.updatedAt = Objects.requireNonNull(now);
    }

    boolean isCompleted() { return status == Status.COMPLETED; }
    UUID getId() { return id; }
    String getRequestHash() { return requestHash; }
    UUID getDocumentId() { return documentId; }
    String getTicketNumber() { return ticketNumber; }
    BigDecimal getTotal() { return total; }
    BigDecimal getReceived() { return received; }
    BigDecimal getChange() { return change; }
    String getTicketSnapshot() { return ticketSnapshot; }
}

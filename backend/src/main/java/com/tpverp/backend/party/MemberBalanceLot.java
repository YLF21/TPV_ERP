package com.tpverp.backend.party;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "member_balance_lot")
public class MemberBalanceLot {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "miembro_id", nullable = false)
    private Member member;
    @Column(name = "documento_id")
    private UUID documentId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_movement_id")
    private MemberMovement sourceMovement;
    @Column(name = "amount_original", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountOriginal;
    @Column(name = "amount_remaining", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountRemaining;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "expired_at")
    private Instant expiredAt;
    @Version
    private long version;

    protected MemberBalanceLot() {
    }

    public MemberBalanceLot(Member member, MemberMovement movement, BigDecimal amount,
            Instant createdAt, Instant expiresAt) {
        var value = PartyValues.money(amount);
        if (value.signum() < 0) {
            throw new IllegalArgumentException("message.member.balance_invalid");
        }
        id = UUID.randomUUID();
        this.member = Objects.requireNonNull(member, "member");
        this.sourceMovement = movement;
        this.amountOriginal = value;
        this.amountRemaining = value;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = expiresAt;
    }

    public void consume(BigDecimal amount) {
        var value = PartyValues.money(amount);
        if (value.signum() <= 0 || amountRemaining.compareTo(value) < 0) {
            throw new IllegalArgumentException("message.member.balance_lot_insufficient");
        }
        amountRemaining = amountRemaining.subtract(value);
    }
    // Decrements an immutable earned lot while keeping the original amount for traceability.

    public BigDecimal expire(Instant now) {
        if (expiredAt != null || amountRemaining.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        var expired = amountRemaining;
        amountRemaining = BigDecimal.ZERO.setScale(2);
        expiredAt = Objects.requireNonNull(now, "now");
        return expired;
    }
    // Closes the remaining amount of a lot when its expiration date is reached.

    public UUID getId() {
        return id;
    }

    public BigDecimal getAmountRemaining() {
        return amountRemaining;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Member getMember() {
        return member;
    }
}

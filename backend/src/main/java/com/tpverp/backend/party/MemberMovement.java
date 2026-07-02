package com.tpverp.backend.party;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "member_movement")
public class MemberMovement {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "empresa_id", nullable = false)
    private Company company;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tienda_id")
    private Store store;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "miembro_id", nullable = false)
    private Member member;
    @Column(name = "documento_id")
    private UUID documentId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MemberMovementType type;
    @Column(name = "balance_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAmount;
    @Column(name = "points_amount", nullable = false)
    private long pointsAmount;
    @Column(name = "previous_category_id")
    private UUID previousCategoryId;
    @Column(name = "new_category_id")
    private UUID newCategoryId;
    private String reason;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private UserAccount createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "source_event_id")
    private UUID sourceEventId;
    @Version
    private long version;

    protected MemberMovement() {
    }

    public MemberMovement(Member member, Store store, UserAccount user, MemberMovementType type,
            BigDecimal balanceAmount, long pointsAmount, UUID previousCategoryId,
            UUID newCategoryId, String reason, Instant now) {
        this(member, store, user, null, type, balanceAmount, pointsAmount,
                previousCategoryId, newCategoryId, reason, now);
    }

    public MemberMovement(Member member, Store store, UserAccount user, UUID documentId,
            MemberMovementType type, BigDecimal balanceAmount, long pointsAmount,
            UUID previousCategoryId, UUID newCategoryId, String reason, Instant now) {
        if (pointsAmount < 0 && type != MemberMovementType.AJUSTE_MANUAL_PUNTOS
                && type != MemberMovementType.AJUSTE_SAAS) {
            throw new IllegalArgumentException("message.member.points_invalid");
        }
        id = UUID.randomUUID();
        this.member = Objects.requireNonNull(member, "member");
        this.company = member.getCompany();
        this.store = store;
        this.createdBy = user;
        this.documentId = documentId;
        this.type = Objects.requireNonNull(type, "type");
        this.balanceAmount = PartyValues.money(balanceAmount);
        this.pointsAmount = pointsAmount;
        this.previousCategoryId = previousCategoryId;
        this.newCategoryId = newCategoryId;
        this.reason = PartyValues.optional(reason);
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    public void setSourceEventId(UUID sourceEventId) {
        this.sourceEventId = sourceEventId;
    }
    // Marks movements created from SaaS events so repeated inbound events stay idempotent.

    public UUID getId() {
        return id;
    }

    public MemberMovementType getType() {
        return type;
    }

    public BigDecimal getBalanceAmount() {
        return balanceAmount;
    }

    public long getPointsAmount() {
        return pointsAmount;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

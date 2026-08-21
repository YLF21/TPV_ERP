package com.tpverp.saas.loyalty;

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
import java.util.UUID;

@Entity
@Table(name = "saas_member_balance_lot")
public class SaasMemberBalanceLot {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private SaasMemberBalanceAccount account;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "original_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal originalAmount;

    @Column(name = "remaining_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal remainingAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "balance_type", nullable = false, length = 20)
    private MemberBalanceType balanceType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "source_movement_id")
    private UUID sourceMovementId;

    @Column(name = "document_id")
    private UUID documentId;

    @Version
    private long version;

    protected SaasMemberBalanceLot() {
    }

    public SaasMemberBalanceLot(
            UUID id,
            SaasMemberBalanceAccount account,
            BigDecimal amount,
            Instant createdAt,
            Instant expiresAt,
            UUID sourceMovementId) {
        this(
                id,
                account,
                MemberBalanceType.LOYALTY,
                amount,
                createdAt,
                expiresAt,
                sourceMovementId,
                null);
    }

    public SaasMemberBalanceLot(
            UUID id,
            SaasMemberBalanceAccount account,
            MemberBalanceType balanceType,
            BigDecimal amount,
            Instant createdAt,
            Instant expiresAt,
            UUID sourceMovementId,
            UUID documentId) {
        this(
                id,
                account,
                balanceType,
                amount,
                amount,
                createdAt,
                expiresAt,
                sourceMovementId,
                documentId);
    }

    public SaasMemberBalanceLot(
            UUID id,
            SaasMemberBalanceAccount account,
            MemberBalanceType balanceType,
            BigDecimal originalAmount,
            BigDecimal remainingAmount,
            Instant createdAt,
            Instant expiresAt,
            UUID sourceMovementId,
            UUID documentId) {
        this.id = id;
        this.account = account;
        this.companyId = account.getCompanyId();
        this.balanceType = balanceType;
        this.originalAmount = originalAmount;
        this.remainingAmount = remainingAmount;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.sourceMovementId = sourceMovementId;
        this.documentId = documentId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getMemberId() {
        return account.getMemberId();
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public BigDecimal getRemainingAmount() {
        return remainingAmount;
    }

    public MemberBalanceType getBalanceType() {
        return balanceType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getSourceMovementId() {
        return sourceMovementId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public BigDecimal expire() {
        BigDecimal expired = remainingAmount;
        remainingAmount = BigDecimal.ZERO.setScale(2);
        return expired;
    }

    public void consume(BigDecimal amount) {
        BigDecimal result = remainingAmount.subtract(amount);
        if (result.signum() < 0) {
            throw new IllegalStateException("El lote central no dispone del saldo reservado");
        }
        remainingAmount = result;
    }
}

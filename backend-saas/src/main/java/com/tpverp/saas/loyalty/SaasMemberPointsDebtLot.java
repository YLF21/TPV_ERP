package com.tpverp.saas.loyalty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saas_member_points_debt_lot")
public class SaasMemberPointsDebtLot {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "origin_operation_id", nullable = false)
    private UUID originOperationId;

    @Column(name = "origin_document_id")
    private UUID originDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_kind", nullable = false, length = 40)
    private MemberPointsDebtOrigin originKind;

    @Column(name = "original_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal originalAmount;

    @Column(name = "remaining_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal remainingAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private MemberPointsDebtLotStatus status;

    @Column(name = "created_sequence", nullable = false)
    private long createdSequence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected SaasMemberPointsDebtLot() {
    }

    public SaasMemberPointsDebtLot(
            UUID id,
            UUID companyId,
            UUID memberId,
            UUID originOperationId,
            UUID originDocumentId,
            MemberPointsDebtOrigin originKind,
            BigDecimal amount,
            long createdSequence,
            Instant createdAt
    ) {
        this.id = id;
        this.companyId = companyId;
        this.memberId = memberId;
        this.originOperationId = originOperationId;
        this.originDocumentId = originDocumentId;
        this.originKind = originKind;
        this.originalAmount = amount;
        this.remainingAmount = amount;
        this.status = MemberPointsDebtLotStatus.ACTIVE;
        this.createdSequence = createdSequence;
        this.createdAt = createdAt;
    }

    public SaasMemberPointsDebtLot(
            UUID id, UUID companyId, UUID memberId, UUID originOperationId,
            UUID originDocumentId, MemberPointsOperationType originType,
            BigDecimal amount, long createdSequence, Instant createdAt) {
        this(id, companyId, memberId, originOperationId, originDocumentId,
                MemberPointsDebtOrigin.fromOperation(originType), amount, createdSequence, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOriginOperationId() {
        return originOperationId;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public BigDecimal getRemainingAmount() {
        return remainingAmount;
    }

    public boolean isActive() {
        return status == MemberPointsDebtLotStatus.ACTIVE;
    }

    public void settle(BigDecimal amount) {
        remainingAmount = remainingAmount.subtract(amount);
    }

    public void reopen(BigDecimal amount) {
        remainingAmount = remainingAmount.add(amount);
    }

    public void cancel(Instant now) {
        remainingAmount = BigDecimal.ZERO;
        status = MemberPointsDebtLotStatus.CANCELLED;
        cancelledAt = now;
    }
}

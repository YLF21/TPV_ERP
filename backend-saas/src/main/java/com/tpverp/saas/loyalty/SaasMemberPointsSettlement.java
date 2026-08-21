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
@Table(name = "saas_member_points_settlement")
public class SaasMemberPointsSettlement {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "original_document_id")
    private UUID originalDocumentId;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_type", nullable = false, length = 16)
    private MemberPointsSettlementType settlementType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal amount;

    @Column(name = "points_awarded", nullable = false, precision = 19, scale = 0)
    private BigDecimal pointsAwarded;

    @Column(name = "debt_settled", nullable = false, precision = 19, scale = 0)
    private BigDecimal debtSettled;

    @Column(name = "points_removed", nullable = false, precision = 19, scale = 0)
    private BigDecimal pointsRemoved;

    @Column(name = "debt_created", nullable = false, precision = 19, scale = 0)
    private BigDecimal debtCreated;

    @Column(name = "debt_lot_id")
    private UUID debtLotId;

    @Column(name = "cancelled_by_operation_id")
    private UUID cancelledByOperationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected SaasMemberPointsSettlement() {
    }

    public SaasMemberPointsSettlement(
            UUID id,
            UUID companyId,
            UUID memberId,
            UUID documentId,
            UUID originalDocumentId,
            UUID operationId,
            MemberPointsSettlementType settlementType,
            BigDecimal amount,
            BigDecimal pointsAwarded,
            BigDecimal debtSettled,
            BigDecimal pointsRemoved,
            BigDecimal debtCreated,
            UUID debtLotId,
            Instant createdAt
    ) {
        this.id = id;
        this.companyId = companyId;
        this.memberId = memberId;
        this.documentId = documentId;
        this.originalDocumentId = originalDocumentId;
        this.operationId = operationId;
        this.settlementType = settlementType;
        this.amount = amount;
        this.pointsAwarded = pointsAwarded;
        this.debtSettled = debtSettled;
        this.pointsRemoved = pointsRemoved;
        this.debtCreated = debtCreated;
        this.debtLotId = debtLotId;
        this.createdAt = createdAt;
    }

    public UUID getMemberId() {
        return memberId;
    }

    public UUID getOperationId() {
        return operationId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getPointsAwarded() {
        return pointsAwarded;
    }

    public BigDecimal getPointsRemoved() {
        return pointsRemoved;
    }

    public BigDecimal getDebtCreated() {
        return debtCreated;
    }

    public UUID getDebtLotId() {
        return debtLotId;
    }

    public boolean isCancelled() {
        return cancelledByOperationId != null;
    }

    public void cancel(UUID cancellationOperationId, Instant now) {
        cancelledByOperationId = cancellationOperationId;
        cancelledAt = now;
    }
}

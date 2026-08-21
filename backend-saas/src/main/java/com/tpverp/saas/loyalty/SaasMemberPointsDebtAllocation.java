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
@Table(name = "saas_member_points_debt_allocation")
public class SaasMemberPointsDebtAllocation {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "sale_settlement_id", nullable = false)
    private UUID saleSettlementId;

    @Column(name = "debt_lot_id", nullable = false)
    private UUID debtLotId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private MemberPointsDebtAllocationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected SaasMemberPointsDebtAllocation() {
    }

    public SaasMemberPointsDebtAllocation(
            UUID companyId,
            UUID memberId,
            UUID saleSettlementId,
            UUID debtLotId,
            BigDecimal amount,
            Instant createdAt
    ) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.memberId = memberId;
        this.saleSettlementId = saleSettlementId;
        this.debtLotId = debtLotId;
        this.amount = amount;
        this.status = MemberPointsDebtAllocationStatus.APPLIED;
        this.createdAt = createdAt;
    }

    public UUID getDebtLotId() {
        return debtLotId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public MemberPointsDebtAllocationStatus getStatus() {
        return status;
    }

    public void reopen(Instant now) {
        status = MemberPointsDebtAllocationStatus.REOPENED;
        reversedAt = now;
    }

    public void convertToPointsReversal(Instant now) {
        status = MemberPointsDebtAllocationStatus.CONVERTED_TO_POINTS_REVERSAL;
        reversedAt = now;
    }
}

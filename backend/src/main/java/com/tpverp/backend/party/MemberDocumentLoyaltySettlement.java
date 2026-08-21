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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "member_document_loyalty_settlement")
public class MemberDocumentLoyaltySettlement {

    @Id
    @Column(name = "documento_id")
    private UUID documentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "miembro_id", nullable = false)
    private Member member;

    @Column(name = "document_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal documentAmount;

    @Column(name = "eligible_document_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal eligibleDocumentAmount;

    @Column(name = "eligible_paid_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal eligiblePaidAmount;

    @Column(name = "generated_points", nullable = false)
    private long generatedPoints;

    @Column(name = "granted_points", nullable = false)
    private long grantedPoints;

    @Column(name = "points_applied_to_debt", nullable = false)
    private long pointsAppliedToDebt;

    @Column(name = "deferred_points", nullable = false)
    private long deferredPoints;

    @Column(name = "generated_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal generatedBalance;

    @Column(name = "granted_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal grantedBalance;

    @Column(name = "balance_applied_to_debt", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAppliedToDebt;

    @Column(name = "member_balance_used", nullable = false, precision = 19, scale = 2)
    private BigDecimal memberBalanceUsed;

    @Column(name = "reversed_eligible_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal reversedEligibleAmount;

    @Column(name = "reversed_points", nullable = false)
    private long reversedPoints;

    @Column(name = "reversed_deferred_points", nullable = false)
    private long reversedDeferredPoints;

    @Column(name = "reversed_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal reversedBalance;

    @Column(name = "restored_member_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal restoredMemberBalance;

    @Column(name = "return_points_debt_created", nullable = false)
    private long returnPointsDebtCreated;

    @Column(name = "return_balance_debt_created", nullable = false, precision = 19, scale = 2)
    private BigDecimal returnBalanceDebtCreated;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected MemberDocumentLoyaltySettlement() {
    }

    public MemberDocumentLoyaltySettlement(
            UUID documentId,
            Member member,
            BigDecimal documentAmount,
            BigDecimal eligibleDocumentAmount,
            Instant now) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.member = Objects.requireNonNull(member, "member");
        this.documentAmount = nonNegativeMoney(documentAmount, "documentAmount");
        this.eligibleDocumentAmount = nonNegativeMoney(
                eligibleDocumentAmount, "eligibleDocumentAmount");
        if (this.eligibleDocumentAmount.compareTo(this.documentAmount) > 0) {
            throw new IllegalArgumentException(
                    "La base elegible no puede superar el total del documento");
        }
        this.eligiblePaidAmount = zero();
        this.generatedBalance = zero();
        this.grantedBalance = zero();
        this.balanceAppliedToDebt = zero();
        this.memberBalanceUsed = zero();
        this.reversedEligibleAmount = zero();
        this.reversedBalance = zero();
        this.restoredMemberBalance = zero();
        this.returnBalanceDebtCreated = zero();
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public void verifyAndUpdateEligibility(
            BigDecimal total,
            BigDecimal amount,
            Instant now) {
        var totalValue = nonNegativeMoney(total, "documentAmount");
        var value = nonNegativeMoney(amount, "eligibleDocumentAmount");
        if (documentAmount.compareTo(totalValue) != 0) {
            throw new IllegalStateException(
                    "El total historico del documento no coincide");
        }
        if (value.compareTo(totalValue) > 0) {
            throw new IllegalArgumentException(
                    "La base elegible no puede superar el total del documento");
        }
        if (eligibleDocumentAmount.signum() > 0
                && value.signum() > 0
                && eligibleDocumentAmount.compareTo(value) != 0) {
            throw new IllegalStateException(
                    "La base historica de fidelizacion del documento no coincide");
        }
        if (eligibleDocumentAmount.signum() == 0 && value.signum() > 0) {
            eligibleDocumentAmount = value;
        }
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void recordAccrual(
            BigDecimal eligiblePaid,
            long points,
            long availablePoints,
            long debtPoints,
            BigDecimal balance,
            BigDecimal availableBalance,
            BigDecimal debtBalance,
            Instant now) {
        recordAccrual(
                eligiblePaid, points, availablePoints, debtPoints, 0L,
                balance, availableBalance, debtBalance, now);
    }

    public void recordAccrual(
            BigDecimal eligiblePaid,
            long points,
            long availablePoints,
            long debtPoints,
            long deferredPoints,
            BigDecimal balance,
            BigDecimal availableBalance,
            BigDecimal debtBalance,
            Instant now) {
        var paid = nonNegativeMoney(eligiblePaid, "eligiblePaid");
        var generatedBalanceValue = nonNegativeMoney(balance, "balance");
        var availableBalanceValue = nonNegativeMoney(
                availableBalance, "availableBalance");
        var debtBalanceValue = nonNegativeMoney(debtBalance, "debtBalance");
        if (points < 0 || availablePoints < 0 || debtPoints < 0 || deferredPoints < 0
                || Math.addExact(
                        Math.addExact(availablePoints, debtPoints),
                        deferredPoints) != points
                || availableBalanceValue.add(debtBalanceValue)
                        .compareTo(generatedBalanceValue) != 0) {
            throw new IllegalArgumentException(
                    "El desglose generado de fidelizacion no es valido");
        }
        if (eligibleDocumentAmount.signum() > 0
                && eligiblePaidAmount.add(paid).compareTo(eligibleDocumentAmount) > 0) {
            throw new IllegalStateException(
                    "El importe cobrado elegible supera la base del documento");
        }
        eligiblePaidAmount = eligiblePaidAmount.add(paid);
        generatedPoints = Math.addExact(generatedPoints, points);
        grantedPoints = Math.addExact(grantedPoints, availablePoints);
        pointsAppliedToDebt = Math.addExact(pointsAppliedToDebt, debtPoints);
        this.deferredPoints = Math.addExact(this.deferredPoints, deferredPoints);
        generatedBalance = generatedBalance.add(generatedBalanceValue);
        grantedBalance = grantedBalance.add(availableBalanceValue);
        balanceAppliedToDebt = balanceAppliedToDebt.add(debtBalanceValue);
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void updateMemberBalanceUsed(BigDecimal amount, Instant now) {
        var value = nonNegativeMoney(amount, "memberBalanceUsed");
        if (value.compareTo(memberBalanceUsed) < 0) {
            throw new IllegalStateException(
                    "El saldo de miembro usado por el documento no puede disminuir");
        }
        memberBalanceUsed = value;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public ReversalPlan planReversal(
            BigDecimal cumulativeRefund,
            BigDecimal cumulativeEligibleRefund) {
        var refund = nonNegativeMoney(cumulativeRefund, "cumulativeRefund")
                .min(documentAmount);
        var eligibleRefund = nonNegativeMoney(
                cumulativeEligibleRefund, "cumulativeEligibleRefund")
                .min(eligibleDocumentAmount);
        if (eligibleRefund.compareTo(reversedEligibleAmount) < 0) {
            throw new IllegalStateException(
                    "La devolucion elegible acumulada no puede disminuir");
        }

        var previousPoints = proratedPoints(
                generatedPoints, reversedEligibleAmount, eligibleDocumentAmount);
        var targetPoints = proratedPoints(
                generatedPoints, eligibleRefund, eligibleDocumentAmount);
        var previousDebtPoints = proratedPoints(
                pointsAppliedToDebt, reversedEligibleAmount, eligibleDocumentAmount);
        var targetDebtPoints = proratedPoints(
                pointsAppliedToDebt, eligibleRefund, eligibleDocumentAmount);
        var previousDeferredPoints = proratedPoints(
                deferredPoints, reversedEligibleAmount, eligibleDocumentAmount);
        var targetDeferredPoints = proratedPoints(
                deferredPoints, eligibleRefund, eligibleDocumentAmount);

        var previousBalance = proratedMoney(
                generatedBalance, reversedEligibleAmount, eligibleDocumentAmount);
        var targetBalance = proratedMoney(
                generatedBalance, eligibleRefund, eligibleDocumentAmount);
        var previousDebtBalance = proratedMoney(
                balanceAppliedToDebt, reversedEligibleAmount, eligibleDocumentAmount);
        var targetDebtBalance = proratedMoney(
                balanceAppliedToDebt, eligibleRefund, eligibleDocumentAmount);

        var targetRestored = proratedMoney(
                memberBalanceUsed, refund, documentAmount);
        if (targetRestored.compareTo(restoredMemberBalance) < 0) {
            throw new IllegalStateException(
                    "El saldo restaurado acumulado no puede disminuir");
        }

        return new ReversalPlan(
                eligibleRefund,
                targetPoints,
                (targetPoints - targetDebtPoints - targetDeferredPoints)
                        - (previousPoints - previousDebtPoints - previousDeferredPoints),
                targetDebtPoints - previousDebtPoints,
                targetDeferredPoints - previousDeferredPoints,
                targetBalance,
                targetBalance.subtract(targetDebtBalance)
                        .subtract(previousBalance.subtract(previousDebtBalance)),
                targetDebtBalance.subtract(previousDebtBalance),
                targetRestored,
                targetRestored.subtract(restoredMemberBalance));
    }

    public void recordReversal(
            ReversalPlan plan,
            long pointsDebtCreated,
            BigDecimal balanceDebtCreated,
            Instant now) {
        recordReversal(plan, pointsDebtCreated, balanceDebtCreated, 0L, now);
    }

    public void recordReversal(
            ReversalPlan plan,
            long pointsDebtCreated,
            BigDecimal balanceDebtCreated,
            long deferredReversalPoints,
            Instant now) {
        Objects.requireNonNull(plan, "plan");
        var balanceDebt = nonNegativeMoney(
                balanceDebtCreated, "balanceDebtCreated");
        if (pointsDebtCreated < 0 || deferredReversalPoints < 0) {
            throw new IllegalArgumentException(
                    "La deuda de puntos creada no puede ser negativa");
        }
        if (plan.eligibleAmount().compareTo(reversedEligibleAmount) < 0
                || plan.points() < reversedPoints
                || plan.balance().compareTo(reversedBalance) < 0
                || plan.restoredMemberBalance().compareTo(restoredMemberBalance) < 0) {
            throw new IllegalStateException(
                    "La liquidacion de devolucion no puede retroceder");
        }
        reversedEligibleAmount = plan.eligibleAmount();
        reversedPoints = plan.points();
        reversedDeferredPoints = Math.addExact(
                reversedDeferredPoints, deferredReversalPoints);
        reversedBalance = plan.balance();
        restoredMemberBalance = plan.restoredMemberBalance();
        returnPointsDebtCreated = Math.addExact(
                returnPointsDebtCreated, pointsDebtCreated);
        returnBalanceDebtCreated = returnBalanceDebtCreated.add(balanceDebt);
        updatedAt = Objects.requireNonNull(now, "now");
    }

    private static long proratedPoints(
            long amount,
            BigDecimal numerator,
            BigDecimal denominator) {
        if (amount == 0 || numerator.signum() == 0 || denominator.signum() == 0) {
            return 0;
        }
        if (numerator.compareTo(denominator) >= 0) {
            return amount;
        }
        return BigDecimal.valueOf(amount).multiply(numerator)
                .divide(denominator, 0, RoundingMode.DOWN)
                .longValueExact();
    }

    private static BigDecimal proratedMoney(
            BigDecimal amount,
            BigDecimal numerator,
            BigDecimal denominator) {
        if (amount.signum() == 0 || numerator.signum() == 0 || denominator.signum() == 0) {
            return zero();
        }
        if (numerator.compareTo(denominator) >= 0) {
            return amount;
        }
        return amount.multiply(numerator)
                .divide(denominator, 8, RoundingMode.DOWN)
                .setScale(2, RoundingMode.DOWN);
    }

    private static BigDecimal nonNegativeMoney(BigDecimal value, String field) {
        var money = PartyValues.money(value);
        if (money.signum() < 0) {
            throw new IllegalArgumentException(field + " no puede ser negativo");
        }
        return money;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public Member getMember() {
        return member;
    }

    public BigDecimal getEligibleDocumentAmount() {
        return eligibleDocumentAmount;
    }

    public BigDecimal getDocumentAmount() {
        return documentAmount;
    }

    public BigDecimal getEligiblePaidAmount() {
        return eligiblePaidAmount;
    }

    public long getGeneratedPoints() {
        return generatedPoints;
    }

    public long getGrantedPoints() {
        return grantedPoints;
    }

    public long getPointsAppliedToDebt() {
        return pointsAppliedToDebt;
    }

    public long getDeferredPoints() {
        return deferredPoints;
    }

    public BigDecimal getGeneratedBalance() {
        return generatedBalance;
    }

    public BigDecimal getGrantedBalance() {
        return grantedBalance;
    }

    public BigDecimal getBalanceAppliedToDebt() {
        return balanceAppliedToDebt;
    }

    public BigDecimal getMemberBalanceUsed() {
        return memberBalanceUsed;
    }

    public BigDecimal getReversedEligibleAmount() {
        return reversedEligibleAmount;
    }

    public long getReversedPoints() {
        return reversedPoints;
    }

    public long getReversedDeferredPoints() {
        return reversedDeferredPoints;
    }

    public void deferCancellation(Instant now) {
        reversedDeferredPoints = generatedPoints;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public BigDecimal getReversedBalance() {
        return reversedBalance;
    }

    public BigDecimal getRestoredMemberBalance() {
        return restoredMemberBalance;
    }

    public long getReturnPointsDebtCreated() {
        return returnPointsDebtCreated;
    }

    public BigDecimal getReturnBalanceDebtCreated() {
        return returnBalanceDebtCreated;
    }

    public long getReversedOriginalPointsDebt() {
        return proratedPoints(
                pointsAppliedToDebt,
                reversedEligibleAmount,
                eligibleDocumentAmount);
    }

    public BigDecimal getReversedOriginalBalanceDebt() {
        return proratedMoney(
                balanceAppliedToDebt,
                reversedEligibleAmount,
                eligibleDocumentAmount);
    }

    public record ReversalPlan(
            BigDecimal eligibleAmount,
            long points,
            long grantedPointsDelta,
            long debtPointsDelta,
            long deferredPointsDelta,
            BigDecimal balance,
            BigDecimal grantedBalanceDelta,
            BigDecimal debtBalanceDelta,
            BigDecimal restoredMemberBalance,
            BigDecimal memberBalanceRestoreDelta) {
    }
}

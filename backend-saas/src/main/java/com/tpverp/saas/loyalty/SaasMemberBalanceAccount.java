package com.tpverp.saas.loyalty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saas_member_balance_account")
public class SaasMemberBalanceAccount {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(name = "return_credit_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal returnCreditBalance;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal points;

    @Column(name = "points_debt", nullable = false, precision = 19, scale = 0)
    private BigDecimal pointsDebt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected SaasMemberBalanceAccount() {
    }

    public SaasMemberBalanceAccount(UUID companyId, UUID memberId) {
        this(
                UUID.randomUUID(),
                companyId,
                memberId,
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(4),
                Instant.now());
    }

    public SaasMemberBalanceAccount(
            UUID id,
            UUID companyId,
            UUID memberId,
            BigDecimal balance,
            BigDecimal points,
            Instant updatedAt) {
        this(
                id,
                companyId,
                memberId,
                balance,
                BigDecimal.ZERO.setScale(2),
                points,
                updatedAt);
    }

    public SaasMemberBalanceAccount(
            UUID id,
            UUID companyId,
            UUID memberId,
            BigDecimal loyaltyBalance,
            BigDecimal returnCreditBalance,
            BigDecimal points,
            Instant updatedAt) {
        this.id = id;
        this.companyId = companyId;
        this.memberId = memberId;
        this.balance = loyaltyBalance;
        this.returnCreditBalance = returnCreditBalance;
        this.points = points;
        this.pointsDebt = BigDecimal.ZERO;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getMemberId() {
        return memberId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public BigDecimal getReturnCreditBalance() {
        return returnCreditBalance;
    }

    public BigDecimal getPoints() {
        return points;
    }

    public BigDecimal getPointsDebt() {
        return pointsDebt;
    }

    public void replacePoints(BigDecimal newPoints, BigDecimal newPointsDebt) {
        points = newPoints;
        pointsDebt = newPointsDebt;
        updatedAt = Instant.now();
    }

    public void credit(MemberBalanceType balanceType, BigDecimal amount, Instant now) {
        if (balanceType == null || amount == null) {
            throw new IllegalArgumentException("El tipo y el importe del saldo son obligatorios");
        }
        BigDecimal normalized = amount.setScale(2, RoundingMode.UNNECESSARY);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException("El importe a acreditar debe ser positivo");
        }
        if (balanceType == MemberBalanceType.LOYALTY) {
            balance = balance.add(normalized);
        } else {
            returnCreditBalance = returnCreditBalance.add(normalized);
        }
        updatedAt = now;
    }

    public void replaceBalances(
            BigDecimal loyaltyBalance,
            BigDecimal newReturnCreditBalance,
            Instant now) {
        BigDecimal normalizedLoyalty = loyaltyBalance.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal normalizedReturnCredit = newReturnCreditBalance.setScale(2, RoundingMode.UNNECESSARY);
        if (normalizedLoyalty.signum() < 0 || normalizedReturnCredit.signum() < 0) {
            throw new IllegalArgumentException("Los saldos centrales no pueden ser negativos");
        }
        balance = normalizedLoyalty;
        returnCreditBalance = normalizedReturnCredit;
        updatedAt = now;
    }

    public void debit(BigDecimal amount, Instant now) {
        debit(MemberBalanceType.LOYALTY, amount, now);
    }

    public void debit(MemberBalanceType balanceType, BigDecimal amount, Instant now) {
        BigDecimal current = balanceType == MemberBalanceType.LOYALTY
                ? balance
                : returnCreditBalance;
        BigDecimal result = current.subtract(amount);
        if (result.signum() < 0) {
            throw new IllegalStateException("El saldo central " + balanceType + " no puede quedar negativo");
        }
        if (balanceType == MemberBalanceType.LOYALTY) {
            balance = result;
        } else {
            returnCreditBalance = result;
        }
        updatedAt = now;
    }
}

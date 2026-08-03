package com.tpverp.backend.party;

import com.tpverp.backend.organization.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "miembro")
public class Member {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "empresa_id", nullable = false)
    private Company company;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cliente_id", nullable = false)
    private Customer customer;

    @Column(name = "member_id", nullable = false, length = 12, updatable = false)
    private String memberId;

    @Column(name = "member_code_store_id", updatable = false)
    private UUID memberCodeStoreId;

    @Column(name = "num_member")
    private String numMember;

    @Column(name = "member_since", nullable = false, updatable = false)
    private LocalDate memberSince;

    @Column(name = "member_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal memberBalance;

    @Column(name = "member_points", nullable = false)
    private long memberPoints;

    @Column(name = "loyalty_balance_debt", nullable = false, precision = 19, scale = 2)
    private BigDecimal loyaltyBalanceDebt;

    @Column(name = "loyalty_points_debt", nullable = false)
    private long loyaltyPointsDebt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_category_id")
    private MemberCategory memberCategory;

    @Column(name = "auto_category_locked", nullable = false)
    private boolean autoCategoryLocked;

    @Column(name = "official_member_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal officialMemberBalance;

    @Column(name = "official_member_points", nullable = false)
    private long officialMemberPoints;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "official_category_id")
    private MemberCategory officialCategory;

    @Column(name = "official_synced_at")
    private Instant officialSyncedAt;

    @Column(nullable = false)
    private boolean active;

    @Version
    private long version;

    protected Member() {
    }

    public Member(Customer customer, String code, LocalDate date) {
        this.id = UUID.randomUUID();
        this.company = Objects.requireNonNull(customer.getCompany(), "empresa");
        this.customer = Objects.requireNonNull(customer, "cliente");
        this.memberId = PartyValues.required(code, "memberId");
        this.memberSince = Objects.requireNonNull(date, "memberSince");
        this.memberBalance = BigDecimal.ZERO.setScale(2);
        this.loyaltyBalanceDebt = BigDecimal.ZERO.setScale(2);
        this.officialMemberBalance = BigDecimal.ZERO.setScale(2);
        this.active = true;
    }

    public void assignMemberStore(UUID storeId) {
        if (memberCodeStoreId == null) {
            memberCodeStoreId = Objects.requireNonNull(storeId, "tienda");
        }
    }

    public void activate() {
        active = true;
    }

    public void deactivate() {
        active = false;
    }

    public void setCategory(MemberCategory category, boolean lockAutomatic) {
        memberCategory = category;
        autoCategoryLocked = lockAutomatic;
    }
    // Changes category while preserving the flag that prevents automatic recategorization.

    public void setNumMember(String value) {
        numMember = PartyValues.optional(value);
    }

    public void applyBalance(BigDecimal amount) {
        if (!active) {
            throw new IllegalStateException("Solo los clientes MEMBER activos tienen saldo");
        }
        changeBalance(amount);
    }

    void applyReturnBalance(BigDecimal amount) {
        changeBalance(amount);
    }
    // Return compensation must also update inactive historical members.

    public void expireBalance(BigDecimal amount) {
        changeBalance(PartyValues.money(amount).negate());
    }
    // Allows automatic expiration even if the member is currently inactive.

    private void changeBalance(BigDecimal amount) {
        BigDecimal updated = memberBalance.add(PartyValues.money(amount));
        if (updated.signum() < 0) {
            throw new IllegalArgumentException("El saldo no puede ser negativo");
        }
        memberBalance = updated;
    }

    public void applyPoints(long points) {
        long updated = memberPoints + points;
        if (updated < 0) {
            throw new IllegalArgumentException("Los puntos no pueden ser negativos");
        }
        memberPoints = updated;
    }

    public void addLoyaltyDebt(BigDecimal balance, long points) {
        var balanceValue = PartyValues.money(balance);
        if (balanceValue.signum() < 0 || points < 0) {
            throw new IllegalArgumentException(
                    "La deuda de fidelizacion no puede ser negativa");
        }
        loyaltyBalanceDebt = loyaltyBalanceDebt.add(balanceValue);
        loyaltyPointsDebt = Math.addExact(loyaltyPointsDebt, points);
    }

    public BigDecimal repayBalanceDebt(BigDecimal earned) {
        var value = PartyValues.money(earned);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(
                    "El saldo generado no puede ser negativo");
        }
        var repaid = value.min(loyaltyBalanceDebt);
        loyaltyBalanceDebt = loyaltyBalanceDebt.subtract(repaid);
        return repaid;
    }

    public long repayPointsDebt(long earned) {
        if (earned < 0) {
            throw new IllegalArgumentException(
                    "Los puntos generados no pueden ser negativos");
        }
        var repaid = Math.min(earned, loyaltyPointsDebt);
        loyaltyPointsDebt -= repaid;
        return repaid;
    }

    public void applyOfficialState(BigDecimal balance, long points, MemberCategory category, Instant syncedAt) {
        var value = PartyValues.money(balance);
        if (value.signum() < 0) {
            throw new IllegalArgumentException("message.member.balance_invalid");
        }
        if (points < 0) {
            throw new IllegalArgumentException("Los puntos oficiales no pueden ser negativos");
        }
        memberBalance = value;
        memberPoints = points;
        memberCategory = category;
        officialMemberPoints = points;
        officialMemberBalance = value;
        officialCategory = category;
        officialSyncedAt = syncedAt;
    }
    // Applies the SaaS authoritative member state to the local store copy.

    public UUID getId() {
        return id;
    }

    public Company getCompany() {
        return company;
    }

    public Customer getCustomer() {
        return customer;
    }

    public String getMemberId() {
        return memberId;
    }

    public String getNumMember() {
        return numMember;
    }

    public LocalDate getMemberSince() {
        return memberSince;
    }

    public BigDecimal getMemberBalance() {
        return memberBalance;
    }

    public long getMemberPoints() {
        return memberPoints;
    }

    public BigDecimal getLoyaltyBalanceDebt() {
        return loyaltyBalanceDebt;
    }

    public long getLoyaltyPointsDebt() {
        return loyaltyPointsDebt;
    }

    public MemberCategory getMemberCategory() {
        return memberCategory;
    }

    public boolean isAutoCategoryLocked() {
        return autoCategoryLocked;
    }

    public BigDecimal getOfficialMemberBalance() {
        return officialMemberBalance;
    }

    public long getOfficialMemberPoints() {
        return officialMemberPoints;
    }

    public Instant getOfficialSyncedAt() {
        return officialSyncedAt;
    }

    public boolean isActive() {
        return active;
    }
}

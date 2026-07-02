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

    public void setNumMember(String value) {
        numMember = PartyValues.optional(value);
    }

    public void applyBalance(BigDecimal amount) {
        if (!active) {
            throw new IllegalStateException("Solo los clientes MEMBER activos tienen saldo");
        }
        BigDecimal updated = memberBalance.add(PartyValues.money(amount));
        if (updated.signum() < 0) {
            throw new IllegalArgumentException("El saldo no puede ser negativo");
        }
        memberBalance = updated;
    }

    public UUID getId() {
        return id;
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

    public boolean isActive() {
        return active;
    }
}

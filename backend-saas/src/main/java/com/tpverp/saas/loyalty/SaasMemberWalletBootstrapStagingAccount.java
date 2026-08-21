package com.tpverp.saas.loyalty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "saas_member_wallet_bootstrap_staging_account")
public class SaasMemberWalletBootstrapStagingAccount {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_row_id", nullable = false)
    private SaasMemberWalletBootstrapSnapshot snapshot;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "loyalty_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal loyaltyBalance;

    @Column(name = "return_credit_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal returnCreditBalance;

    protected SaasMemberWalletBootstrapStagingAccount() {
    }

    public SaasMemberWalletBootstrapStagingAccount(
            UUID id,
            SaasMemberWalletBootstrapSnapshot snapshot,
            UUID memberId,
            BigDecimal loyaltyBalance,
            BigDecimal returnCreditBalance) {
        this.id = id;
        this.snapshot = snapshot;
        this.memberId = memberId;
        this.loyaltyBalance = loyaltyBalance;
        this.returnCreditBalance = returnCreditBalance;
    }

    public SaasMemberWalletBootstrapSnapshot getSnapshot() {
        return snapshot;
    }

    public UUID getMemberId() {
        return memberId;
    }

    public BigDecimal getLoyaltyBalance() {
        return loyaltyBalance;
    }

    public BigDecimal getReturnCreditBalance() {
        return returnCreditBalance;
    }
}

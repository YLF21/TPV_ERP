package com.tpverp.backend.party.loyalty.bootstrap;

import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.SnapshotAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "member_wallet_bootstrap_snapshot_account",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_member_wallet_bootstrap_account_member",
                columnNames = {"snapshot_id", "member_id"}))
public class MemberWalletBootstrapSnapshotAccount {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private MemberWalletBootstrapSnapshot snapshot;
    @Column(name = "member_id", nullable = false)
    private UUID memberId;
    @Column(name = "loyalty_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal loyaltyBalance;
    @Column(name = "return_credit_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal returnCreditBalance;

    protected MemberWalletBootstrapSnapshotAccount() {
    }

    public MemberWalletBootstrapSnapshotAccount(
            MemberWalletBootstrapSnapshot snapshot,
            SnapshotAccount account) {
        this.id = UUID.randomUUID();
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.memberId = account.memberId();
        this.loyaltyBalance = account.loyaltyBalance();
        this.returnCreditBalance = account.returnCreditBalance();
    }

    public SnapshotAccount toContract() {
        return new SnapshotAccount(memberId, loyaltyBalance, returnCreditBalance);
    }
}

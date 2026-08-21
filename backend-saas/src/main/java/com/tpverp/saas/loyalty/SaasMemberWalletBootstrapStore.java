package com.tpverp.saas.loyalty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saas_member_wallet_bootstrap_store")
public class SaasMemberWalletBootstrapStore {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bootstrap_id", nullable = false)
    private SaasMemberWalletBootstrap bootstrap;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "conflict_reason", columnDefinition = "text")
    private String conflictReason;

    protected SaasMemberWalletBootstrapStore() {
    }

    public SaasMemberWalletBootstrapStore(
            UUID id,
            SaasMemberWalletBootstrap bootstrap,
            UUID storeId) {
        this.id = id;
        this.bootstrap = bootstrap;
        this.storeId = storeId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getConflictReason() {
        return conflictReason;
    }

    public void complete(Instant now) {
        if (completedAt == null) {
            completedAt = now;
        }
    }

    public void markConflict(String reason) {
        conflictReason = reason;
    }
}

package com.tpverp.saas.loyalty;

import com.tpverp.saas.license.SaasCompany;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saas_member_wallet_bootstrap")
public class SaasMemberWalletBootstrap {

    public static final String COLLECTING = "COLLECTING";
    public static final String RECONCILING = "RECONCILING";
    public static final String CONFLICT = "CONFLICT";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private SaasCompany company;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "cutoff_at")
    private Instant cutoffAt;

    @Column(name = "conflict_reason", columnDefinition = "text")
    private String conflictReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    private long version;

    protected SaasMemberWalletBootstrap() {
    }

    public SaasMemberWalletBootstrap(UUID id, SaasCompany company, Instant createdAt) {
        this.id = id;
        this.company = company;
        this.status = COLLECTING;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return company.getId();
    }

    public String getStatus() {
        return status;
    }

    public Instant getCutoffAt() {
        return cutoffAt;
    }

    public String getConflictReason() {
        return conflictReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public boolean isCompleted() {
        return COMPLETED.equals(status);
    }

    public boolean isCollecting() {
        return COLLECTING.equals(status);
    }

    public void establishCutoff(Instant value) {
        if (cutoffAt == null) {
            cutoffAt = value;
        }
    }

    public void beginReconciliation() {
        status = RECONCILING;
    }

    public void markConflict(String reason) {
        status = CONFLICT;
        conflictReason = reason;
    }

    public void complete(Instant now) {
        status = COMPLETED;
        completedAt = now;
        conflictReason = null;
    }

    public void cancel(Instant now) {
        status = CANCELLED;
        cancelledAt = now;
    }
}

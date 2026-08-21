package com.tpverp.saas.loyalty;

import com.tpverp.saas.license.SaasCompany;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saas_member_points_bootstrap")
public class SaasMemberPointsBootstrap {
    public static final String COLLECTING = "COLLECTING";
    public static final String CATCHING_UP = "CATCHING_UP";
    public static final String RECONCILING = "RECONCILING";
    public static final String CONFLICT = "CONFLICT";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false) private SaasCompany company;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "cutoff_at") private Instant cutoffAt;
    @Column(name = "conflict_reason", columnDefinition = "text") private String conflictReason;
    @Column(name = "official_revision") private Long officialRevision;
    @Column(name = "central_watermark") private Long centralWatermark;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "cancelled_at") private Instant cancelledAt;
    @Version private long version;

    protected SaasMemberPointsBootstrap() {}

    public SaasMemberPointsBootstrap(UUID id, SaasCompany company, Instant createdAt) {
        this.id = id;
        this.company = company;
        this.status = COLLECTING;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return company.getId(); }
    public String getStatus() { return status; }
    public Instant getCutoffAt() { return cutoffAt; }
    public String getConflictReason() { return conflictReason; }
    public Long getOfficialRevision() { return officialRevision; }
    public Long getCentralWatermark() { return centralWatermark; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public boolean isCompleted() { return COMPLETED.equals(status); }
    public boolean isCancelled() { return CANCELLED.equals(status); }
    public boolean acceptsSnapshots() { return COLLECTING.equals(status) || CATCHING_UP.equals(status); }
    public void establishCutoff(Instant value) { if (cutoffAt == null) cutoffAt = value; }
    public void markCatchingUp() { status = CATCHING_UP; }
    public void beginReconciliation() { status = RECONCILING; }
    public void markConflict(String reason) { if (!isCompleted()) { status = CONFLICT; conflictReason = reason; } }
    public void complete(Instant now, long revision, long watermark) {
        status = COMPLETED;
        completedAt = now;
        officialRevision = revision;
        centralWatermark = watermark;
        conflictReason = null;
    }
    public void cancel(Instant now) { if (!isCompleted()) { status = CANCELLED; cancelledAt = now; } }
}

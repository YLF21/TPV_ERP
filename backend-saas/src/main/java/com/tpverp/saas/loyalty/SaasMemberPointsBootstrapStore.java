package com.tpverp.saas.loyalty;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saas_member_points_bootstrap_store")
public class SaasMemberPointsBootstrapStore {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bootstrap_id", nullable = false) private SaasMemberPointsBootstrap bootstrap;
    @Column(name = "store_id", nullable = false) private UUID storeId;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "conflict_reason", columnDefinition = "text") private String conflictReason;

    protected SaasMemberPointsBootstrapStore() {}
    public SaasMemberPointsBootstrapStore(UUID id, SaasMemberPointsBootstrap bootstrap, UUID storeId) {
        this.id = id; this.bootstrap = bootstrap; this.storeId = storeId;
    }
    public UUID getStoreId() { return storeId; }
    public Instant getCompletedAt() { return completedAt; }
    public String getConflictReason() { return conflictReason; }
    public void complete(Instant now) { if (completedAt == null) completedAt = now; }
    public void markConflict(String reason) { conflictReason = reason; }
}

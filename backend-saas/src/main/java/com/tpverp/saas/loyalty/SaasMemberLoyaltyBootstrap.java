package com.tpverp.saas.loyalty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saas_member_loyalty_bootstrap")
public class SaasMemberLoyaltyBootstrap {

    @Id
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "source_store_id", nullable = false)
    private UUID sourceStoreId;

    @Column(name = "source_installation_id")
    private UUID sourceInstallationId;

    @Column(name = "source_checksum", length = 64)
    private String sourceChecksum;

    @Column(name = "snapshot_at")
    private Instant snapshotAt;

    @Column(name = "designated_at", nullable = false)
    private Instant designatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    protected SaasMemberLoyaltyBootstrap() {
    }

    public SaasMemberLoyaltyBootstrap(
            UUID companyId,
            UUID sourceStoreId,
            Instant designatedAt) {
        this.companyId = companyId;
        this.sourceStoreId = sourceStoreId;
        this.designatedAt = designatedAt;
    }

    public void redesignate(UUID sourceStoreId, Instant designatedAt) {
        if (isCompleted()) {
            throw new IllegalStateException("No se puede cambiar la tienda fuente despues del bootstrap");
        }
        this.sourceStoreId = sourceStoreId;
        this.designatedAt = designatedAt;
    }

    public void complete(
            UUID sourceInstallationId,
            String sourceChecksum,
            Instant snapshotAt,
            Instant completedAt) {
        this.sourceInstallationId = sourceInstallationId;
        this.sourceChecksum = sourceChecksum;
        this.snapshotAt = snapshotAt;
        this.completedAt = completedAt;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getSourceStoreId() {
        return sourceStoreId;
    }

    public UUID getSourceInstallationId() {
        return sourceInstallationId;
    }

    public String getSourceChecksum() {
        return sourceChecksum;
    }

    public Instant getSnapshotAt() {
        return snapshotAt;
    }

    public Instant getDesignatedAt() {
        return designatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public boolean isCompleted() {
        return completedAt != null;
    }
}

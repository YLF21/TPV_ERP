package com.tpverp.saas.loyalty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "saas_member_wallet_bootstrap_chunk")
public class SaasMemberWalletBootstrapChunk {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_row_id", nullable = false)
    private SaasMemberWalletBootstrapSnapshot snapshot;

    @Column(nullable = false, length = 10)
    private String kind;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "chunk_hash", nullable = false, length = 64)
    private String chunkHash;

    @Column(name = "record_count", nullable = false)
    private int recordCount;

    protected SaasMemberWalletBootstrapChunk() {
    }

    public SaasMemberWalletBootstrapChunk(
            UUID id,
            SaasMemberWalletBootstrapSnapshot snapshot,
            String kind,
            int chunkIndex,
            String chunkHash,
            int recordCount) {
        this.id = id;
        this.snapshot = snapshot;
        this.kind = kind;
        this.chunkIndex = chunkIndex;
        this.chunkHash = chunkHash;
        this.recordCount = recordCount;
    }

    public String getKind() {
        return kind;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getChunkHash() {
        return chunkHash;
    }

    public int getRecordCount() {
        return recordCount;
    }
}

package com.tpverp.saas.loyalty;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "saas_member_points_bootstrap_chunk")
public class SaasMemberPointsBootstrapChunk {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_row_id", nullable = false) private SaasMemberPointsBootstrapSnapshot snapshot;
    @Column(nullable = false, length = 24) private String kind;
    @Column(name = "chunk_index", nullable = false) private int chunkIndex;
    @Column(name = "chunk_hash", nullable = false, length = 64) private String chunkHash;
    @Column(name = "record_count", nullable = false) private int recordCount;
    protected SaasMemberPointsBootstrapChunk() {}
    public SaasMemberPointsBootstrapChunk(UUID id, SaasMemberPointsBootstrapSnapshot snapshot,
            String kind, int chunkIndex, String chunkHash, int recordCount) {
        this.id=id; this.snapshot=snapshot; this.kind=kind; this.chunkIndex=chunkIndex;
        this.chunkHash=chunkHash; this.recordCount=recordCount;
    }
    public String getKind(){return kind;} public int getChunkIndex(){return chunkIndex;}
    public String getChunkHash(){return chunkHash;} public int getRecordCount(){return recordCount;}
}

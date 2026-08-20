package com.tpverp.backend.party.loyalty.points.bootstrap;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "member_points_bootstrap_upload")
public class MemberPointsBootstrapUpload {
    @Id
    @Column(name = "snapshot_id")
    private UUID snapshotId;
    @Column(name = "bootstrap_id", nullable = false)
    private UUID bootstrapId;
    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;
    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;
    @Column(name = "seal_sequence", nullable = false)
    private long sealSequence;
    @Column(name = "account_chunk_count", nullable = false)
    private int accountChunkCount;
    @Column(name = "absorbed_chunk_count", nullable = false)
    private int absorbedChunkCount;
    @Column(name = "replay_chunk_count", nullable = false)
    private int replayChunkCount;
    @Column(name = "account_count", nullable = false)
    private int accountCount;
    @Column(name = "absorbed_count", nullable = false)
    private int absorbedCount;
    @Column(name = "replay_count", nullable = false)
    private int replayCount;
    @Column(name = "snapshot_checksum", nullable = false, length = 64)
    private String snapshotChecksum;
    @Column(name = "next_account_chunk", nullable = false)
    private int nextAccountChunk;
    @Column(name = "next_absorbed_chunk", nullable = false)
    private int nextAbsorbedChunk;
    @Column(name = "next_replay_chunk", nullable = false)
    private int nextReplayChunk;
    @Column(name = "begin_sent_at")
    private Instant beginSentAt;
    @Column(name = "submitted_at")
    private Instant submittedAt;
    @Column(name = "official_revision")
    private Long officialRevision;
    @Column(name = "central_watermark")
    private Long centralWatermark;
    @Column(name = "official_total_chunks")
    private Integer officialTotalChunks;
    @Column(name = "next_official_chunk", nullable = false)
    private int nextOfficialChunk;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected MemberPointsBootstrapUpload() {
    }

    public MemberPointsBootstrapUpload(
            MemberPointsBootstrapSnapshot snapshot,
            long sealSequence,
            int accountChunkCount,
            int absorbedChunkCount,
            int replayChunkCount,
            int accountCount,
            int absorbedCount,
            int replayCount,
            String snapshotChecksum,
            Instant now) {
        this.snapshotId = snapshot.getSnapshotId();
        this.bootstrapId = snapshot.getBootstrapId();
        this.companyId = snapshot.getCompanyId();
        this.storeId = snapshot.getStoreId();
        this.sealSequence = sealSequence;
        this.accountChunkCount = accountChunkCount;
        this.absorbedChunkCount = absorbedChunkCount;
        this.replayChunkCount = replayChunkCount;
        this.accountCount = accountCount;
        this.absorbedCount = absorbedCount;
        this.replayCount = replayCount;
        this.snapshotChecksum = Objects.requireNonNull(snapshotChecksum, "snapshotChecksum");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public void requireContext(UUID requestedCompanyId, UUID requestedStoreId) {
        if (!companyId.equals(requestedCompanyId) || !storeId.equals(requestedStoreId)) {
            throw new IllegalStateException("El upload pertenece a otra empresa o tienda");
        }
    }

    public void markBeginSent(Instant now) {
        if (beginSentAt == null) {
            beginSentAt = Objects.requireNonNull(now, "now");
        }
        updatedAt = now;
    }

    public void advanceAccountChunk(int uploadedIndex, Instant now) {
        nextAccountChunk = advance(
                "ACCOUNTS", nextAccountChunk, accountChunkCount, uploadedIndex);
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void advanceAbsorbedChunk(int uploadedIndex, Instant now) {
        nextAbsorbedChunk = advance(
                "ABSORBED_OPERATIONS",
                nextAbsorbedChunk,
                absorbedChunkCount,
                uploadedIndex);
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void advanceReplayChunk(int uploadedIndex, Instant now) {
        nextReplayChunk = advance(
                "REPLAY_OPERATIONS",
                nextReplayChunk,
                replayChunkCount,
                uploadedIndex);
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public boolean allUploadChunksSent() {
        return nextAccountChunk == accountChunkCount
                && nextAbsorbedChunk == absorbedChunkCount
                && nextReplayChunk == replayChunkCount;
    }

    public void markSubmitted(Instant now) {
        if (!allUploadChunksSent()) {
            throw new IllegalStateException("Todavia faltan chunks del bootstrap de puntos");
        }
        if (submittedAt == null) {
            submittedAt = Objects.requireNonNull(now, "now");
        }
        updatedAt = now;
    }

    public void configureOfficialState(
            long requestedRevision,
            long requestedWatermark,
            int requestedTotalChunks,
            Instant now) {
        if (requestedRevision < 0
                || requestedWatermark < 0
                || requestedTotalChunks <= 0) {
            throw new IllegalArgumentException("Metadatos del snapshot oficial invalidos");
        }
        if (officialRevision != null
                && (!officialRevision.equals(requestedRevision)
                        || !centralWatermark.equals(requestedWatermark)
                        || !officialTotalChunks.equals(requestedTotalChunks))) {
            throw new IllegalStateException("El snapshot oficial cambio durante la descarga");
        }
        officialRevision = requestedRevision;
        centralWatermark = requestedWatermark;
        officialTotalChunks = requestedTotalChunks;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void advanceOfficialChunk(int downloadedIndex, Instant now) {
        if (officialTotalChunks == null) {
            throw new IllegalStateException("Faltan metadatos del snapshot oficial");
        }
        nextOfficialChunk = advance(
                "OFFICIAL_STATE",
                nextOfficialChunk,
                officialTotalChunks,
                downloadedIndex);
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public boolean officialStateComplete() {
        return officialTotalChunks != null
                && nextOfficialChunk == officialTotalChunks;
    }

    private static int advance(
            String kind,
            int currentIndex,
            int totalChunks,
            int completedIndex) {
        if (completedIndex < currentIndex) {
            return currentIndex;
        }
        if (completedIndex != currentIndex || completedIndex >= totalChunks) {
            throw new IllegalStateException("Cursor de chunk invalido para " + kind);
        }
        return currentIndex + 1;
    }

    public UUID getSnapshotId() { return snapshotId; }
    public UUID getBootstrapId() { return bootstrapId; }
    public UUID getCompanyId() { return companyId; }
    public UUID getStoreId() { return storeId; }
    public long getSealSequence() { return sealSequence; }
    public int getAccountChunkCount() { return accountChunkCount; }
    public int getAbsorbedChunkCount() { return absorbedChunkCount; }
    public int getReplayChunkCount() { return replayChunkCount; }
    public int getAccountCount() { return accountCount; }
    public int getAbsorbedCount() { return absorbedCount; }
    public int getReplayCount() { return replayCount; }
    public String getSnapshotChecksum() { return snapshotChecksum; }
    public int getNextAccountChunk() { return nextAccountChunk; }
    public int getNextAbsorbedChunk() { return nextAbsorbedChunk; }
    public int getNextReplayChunk() { return nextReplayChunk; }
    public Instant getBeginSentAt() { return beginSentAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Long getOfficialRevision() { return officialRevision; }
    public Long getCentralWatermark() { return centralWatermark; }
    public Integer getOfficialTotalChunks() { return officialTotalChunks; }
    public int getNextOfficialChunk() { return nextOfficialChunk; }
}

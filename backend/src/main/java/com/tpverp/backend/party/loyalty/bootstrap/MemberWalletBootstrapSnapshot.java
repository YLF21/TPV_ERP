package com.tpverp.backend.party.loyalty.bootstrap;

import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.MemberWalletBootstrapStatus;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.MemberWalletBootstrapStatusValue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "member_wallet_bootstrap_snapshot",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_member_wallet_bootstrap_snapshot_store",
                columnNames = {"bootstrap_id", "saas_tienda_id"}))
public class MemberWalletBootstrapSnapshot {

    @Id
    @Column(name = "snapshot_id", nullable = false)
    private UUID snapshotId;
    @Column(name = "bootstrap_id", nullable = false)
    private UUID bootstrapId;
    @Column(name = "empresa_id", nullable = false)
    private UUID localCompanyId;
    @Column(name = "tienda_id", nullable = false)
    private UUID localStoreId;
    @Column(name = "saas_empresa_id", nullable = false)
    private UUID companyId;
    @Column(name = "saas_tienda_id", nullable = false)
    private UUID storeId;
    @Column(name = "cutoff_at", nullable = false)
    private Instant cutoffAt;
    @Column(name = "account_chunk_count", nullable = false)
    private int accountChunkCount;
    @Column(name = "lot_chunk_count", nullable = false)
    private int lotChunkCount;
    @Column(name = "account_count", nullable = false)
    private int accountCount;
    @Column(name = "lot_count", nullable = false)
    private int lotCount;
    @Column(name = "snapshot_checksum", length = 64)
    private String snapshotChecksum;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MemberWalletBootstrapSnapshotStatus status;
    @Column(name = "remote_status", length = 16)
    private String remoteStatus;
    @Column(name = "begin_accepted", nullable = false)
    private boolean beginAccepted;
    @Column(name = "next_account_chunk", nullable = false)
    private int nextAccountChunk;
    @Column(name = "next_lot_chunk", nullable = false)
    private int nextLotChunk;
    @Column(name = "complete_sent", nullable = false)
    private boolean completeSent;
    @Column(name = "attempts", nullable = false)
    private int attempts;
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;
    @Column(name = "last_error", length = 1000)
    private String lastError;
    @Column(name = "conflict_reason", length = 1000)
    private String conflictReason;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "submitted_at")
    private Instant submittedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Version
    private long version;

    protected MemberWalletBootstrapSnapshot() {
    }

    public MemberWalletBootstrapSnapshot(
            UUID bootstrapId,
            UUID localCompanyId,
            UUID localStoreId,
            UUID companyId,
            UUID storeId,
            Instant cutoffAt,
            Instant createdAt) {
        this.snapshotId = UUID.randomUUID();
        this.bootstrapId = Objects.requireNonNull(bootstrapId, "bootstrapId");
        this.localCompanyId = Objects.requireNonNull(localCompanyId, "localCompanyId");
        this.localStoreId = Objects.requireNonNull(localStoreId, "localStoreId");
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.cutoffAt = Objects.requireNonNull(cutoffAt, "cutoffAt");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.nextAttemptAt = createdAt;
        this.status = MemberWalletBootstrapSnapshotStatus.CAPTURING;
    }

    public void finishCapture(
            int accountChunkCount,
            int lotChunkCount,
            int accountCount,
            int lotCount,
            String snapshotChecksum) {
        if (status != MemberWalletBootstrapSnapshotStatus.CAPTURING) {
            throw new IllegalStateException("El snapshot ya fue capturado");
        }
        this.accountChunkCount = accountChunkCount;
        this.lotChunkCount = lotChunkCount;
        this.accountCount = accountCount;
        this.lotCount = lotCount;
        this.snapshotChecksum = requiredHash(snapshotChecksum);
        this.status = MemberWalletBootstrapSnapshotStatus.CAPTURED;
    }

    public void markBeginAccepted(Instant now) {
        requireMutable();
        beginAccepted = true;
        status = MemberWalletBootstrapSnapshotStatus.UPLOADING;
        recordSuccess(now);
    }

    public void markChunkUploaded(
            com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.BootstrapChunkKind kind,
            int index,
            Instant now) {
        requireMutable();
        if (kind == com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.BootstrapChunkKind.ACCOUNTS) {
            if (index != nextAccountChunk || index >= accountChunkCount) {
                throw new IllegalStateException("Indice de chunk ACCOUNTS inesperado");
            }
            nextAccountChunk++;
        } else {
            if (index != nextLotChunk || index >= lotChunkCount) {
                throw new IllegalStateException("Indice de chunk LOTS inesperado");
            }
            nextLotChunk++;
        }
        status = MemberWalletBootstrapSnapshotStatus.UPLOADING;
        recordSuccess(now);
    }

    public void markCompleteSent(Instant now) {
        requireMutable();
        if (!beginAccepted
                || nextAccountChunk != accountChunkCount
                || nextLotChunk != lotChunkCount) {
            throw new IllegalStateException("No se puede completar un snapshot incompleto");
        }
        completeSent = true;
        submittedAt = Objects.requireNonNull(now, "now");
        status = MemberWalletBootstrapSnapshotStatus.SUBMITTED;
        recordSuccess(now);
    }

    public void acknowledgeRemoteStoreCompletion(
            MemberWalletBootstrapStatus remote,
            Instant now) {
        beginAccepted = true;
        nextAccountChunk = accountChunkCount;
        nextLotChunk = lotChunkCount;
        completeSent = true;
        if (submittedAt == null) {
            submittedAt = now;
        }
        applyRemoteStatus(remote, now);
    }

    public void applyRemoteStatus(MemberWalletBootstrapStatus remote, Instant now) {
        Objects.requireNonNull(remote, "remote");
        if (status == MemberWalletBootstrapSnapshotStatus.COMPLETED) {
            return;
        }
        remoteStatus = remote.status().name();
        switch (remote.status()) {
            case COMPLETED -> {
                status = MemberWalletBootstrapSnapshotStatus.COMPLETED;
                completedAt = remote.completedAt() == null ? now : remote.completedAt();
            }
            case CONFLICT -> {
                status = MemberWalletBootstrapSnapshotStatus.CONFLICT;
                conflictReason = trim(remote.conflictReason());
                completedAt = remote.completedAt() == null ? now : remote.completedAt();
            }
            case CANCELLED -> {
                status = MemberWalletBootstrapSnapshotStatus.CANCELLED;
                completedAt = remote.completedAt() == null ? now : remote.completedAt();
            }
            case RECONCILING -> status = MemberWalletBootstrapSnapshotStatus.SUBMITTED;
            case COLLECTING -> status = completeSent
                    ? MemberWalletBootstrapSnapshotStatus.SUBMITTED
                    : beginAccepted
                            ? MemberWalletBootstrapSnapshotStatus.UPLOADING
                            : MemberWalletBootstrapSnapshotStatus.CAPTURED;
        }
        recordSuccess(now);
        if (status == MemberWalletBootstrapSnapshotStatus.CONFLICT) {
            lastError = conflictReason;
        }
    }

    public void markFailure(String error, Instant now) {
        if (isTerminal()) {
            return;
        }
        attempts++;
        lastError = requiredError(error);
        nextAttemptAt = now.plus(backoffSeconds(attempts), ChronoUnit.SECONDS);
    }

    public void markConflict(String reason, Instant now) {
        if (status == MemberWalletBootstrapSnapshotStatus.COMPLETED) {
            return;
        }
        status = MemberWalletBootstrapSnapshotStatus.CONFLICT;
        remoteStatus = MemberWalletBootstrapStatusValue.CONFLICT.name();
        conflictReason = requiredError(reason);
        lastError = conflictReason;
        completedAt = now;
        nextAttemptAt = null;
    }

    public boolean isDue(Instant now) {
        return !isTerminal()
                && (nextAttemptAt == null || !nextAttemptAt.isAfter(now));
    }

    public boolean isTerminal() {
        return status == MemberWalletBootstrapSnapshotStatus.COMPLETED
                || status == MemberWalletBootstrapSnapshotStatus.CONFLICT
                || status == MemberWalletBootstrapSnapshotStatus.CANCELLED;
    }

    private void requireMutable() {
        if (isTerminal()) {
            throw new IllegalStateException("El snapshot ya esta cerrado");
        }
    }

    private void recordSuccess(Instant now) {
        attempts = 0;
        lastError = null;
        nextAttemptAt = isTerminal() ? null : Objects.requireNonNull(now, "now");
    }

    private static long backoffSeconds(int attempts) {
        int exponent = Math.min(Math.max(attempts - 1, 0), 6);
        return Math.min(3600L, 60L * (1L << exponent));
    }

    private static String requiredHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Checksum de snapshot invalido");
        }
        return value;
    }

    private static String requiredError(String value) {
        String result = trim(value);
        return result == null ? "Error de bootstrap sin detalle" : result;
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= 1000 ? trimmed : trimmed.substring(0, 1000);
    }

    public UUID getSnapshotId() {
        return snapshotId;
    }

    public UUID getBootstrapId() {
        return bootstrapId;
    }

    public UUID getLocalCompanyId() {
        return localCompanyId;
    }

    public UUID getLocalStoreId() {
        return localStoreId;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public Instant getCutoffAt() {
        return cutoffAt;
    }

    public int getAccountChunkCount() {
        return accountChunkCount;
    }

    public int getLotChunkCount() {
        return lotChunkCount;
    }

    public int getAccountCount() {
        return accountCount;
    }

    public int getLotCount() {
        return lotCount;
    }

    public String getSnapshotChecksum() {
        return snapshotChecksum;
    }

    public MemberWalletBootstrapSnapshotStatus getStatus() {
        return status;
    }

    public boolean isBeginAccepted() {
        return beginAccepted;
    }

    public int getNextAccountChunk() {
        return nextAccountChunk;
    }

    public int getNextLotChunk() {
        return nextLotChunk;
    }

    public boolean isCompleteSent() {
        return completeSent;
    }
}

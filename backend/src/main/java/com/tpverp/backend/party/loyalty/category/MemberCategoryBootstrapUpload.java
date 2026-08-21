package com.tpverp.backend.party.loyalty.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "member_category_bootstrap_upload")
public class MemberCategoryBootstrapUpload {

    public enum Status {
        PENDING,
        UPLOADING,
        WAITING_CENTRAL,
        APPLIED,
        CONFLICT
    }

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "snapshot_id", nullable = false, unique = true)
    private UUID snapshotId;

    @Column(name = "central_bootstrap_id")
    private UUID centralBootstrapId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status;

    @Column(name = "category_chunks_uploaded", nullable = false)
    private int categoryChunksUploaded;

    @Column(name = "assignment_chunks_uploaded", nullable = false)
    private int assignmentChunksUploaded;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected MemberCategoryBootstrapUpload() {
    }

    private MemberCategoryBootstrapUpload(
            UUID companyId,
            UUID storeId,
            UUID snapshotId,
            Instant now
    ) {
        this.id = UUID.randomUUID();
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        this.status = Status.PENDING;
        this.nextAttemptAt = Objects.requireNonNull(now, "now");
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static MemberCategoryBootstrapUpload pending(
            UUID companyId,
            UUID storeId,
            UUID snapshotId,
            Instant now
    ) {
        return new MemberCategoryBootstrapUpload(companyId, storeId, snapshotId, now);
    }

    public void start(UUID centralBootstrapId, Instant now) {
        this.centralBootstrapId = Objects.requireNonNull(centralBootstrapId, "centralBootstrapId");
        this.status = Status.UPLOADING;
        this.lastError = null;
        this.nextAttemptAt = now;
        this.updatedAt = now;
    }

    public void recordCategoryChunk(Instant now) {
        requireStatus(Status.UPLOADING);
        categoryChunksUploaded++;
        updatedAt = now;
    }

    public void recordAssignmentChunk(Instant now) {
        requireStatus(Status.UPLOADING);
        assignmentChunksUploaded++;
        updatedAt = now;
    }

    public void waitForCentralResult(Instant now) {
        requireStatus(Status.UPLOADING);
        status = Status.WAITING_CENTRAL;
        lastError = null;
        nextAttemptAt = now;
        updatedAt = now;
    }

    public void markApplied(Instant now) {
        status = Status.APPLIED;
        lastError = null;
        nextAttemptAt = now;
        updatedAt = now;
    }

    public void defer(Instant now) {
        if (status != Status.WAITING_CENTRAL) {
            throw new IllegalStateException("Solo se puede aplazar una conciliacion central pendiente");
        }
        lastError = null;
        nextAttemptAt = now.plus(30, ChronoUnit.SECONDS);
        updatedAt = now;
    }

    public void markConflict(String reason, Instant now) {
        status = Status.CONFLICT;
        lastError = normalizeError(reason);
        nextAttemptAt = now;
        updatedAt = now;
    }

    public void scheduleRetry(String reason, Instant now) {
        attempts++;
        lastError = normalizeError(reason);
        nextAttemptAt = now.plus(retryDelaySeconds(attempts), ChronoUnit.SECONDS);
        updatedAt = now;
    }

    private void requireStatus(Status expected) {
        if (status != expected) {
            throw new IllegalStateException("Estado de bootstrap de categorías no válido: " + status);
        }
    }

    private static long retryDelaySeconds(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 8);
        return Math.min(300L, 1L << exponent);
    }

    private static String normalizeError(String reason) {
        String value = reason == null || reason.isBlank() ? "Error de sincronización" : reason.trim();
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getSnapshotId() {
        return snapshotId;
    }

    public UUID getCentralBootstrapId() {
        return centralBootstrapId;
    }

    public Status getStatus() {
        return status;
    }

    public int getCategoryChunksUploaded() {
        return categoryChunksUploaded;
    }

    public int getAssignmentChunksUploaded() {
        return assignmentChunksUploaded;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }
}

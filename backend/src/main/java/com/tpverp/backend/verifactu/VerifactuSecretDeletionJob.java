package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "verifactu_secret_deletion_job")
class VerifactuSecretDeletionJob {

    @Id
    private UUID id;
    @Column(name = "company_id", nullable = false)
    private UUID companyId;
    @Column(name = "certificate_id")
    private UUID certificateId;
    @Column(name = "secret_path", nullable = false, length = 512)
    private String secretPath;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private VerifactuSecretDeletionReason reason;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VerifactuSecretDeletionStatus status;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;
    @Column(name = "processing_owner")
    private UUID processingOwner;
    @Column(name = "processing_lease_until")
    private Instant processingLeaseUntil;
    @Column(name = "last_error", length = 128)
    private String lastError;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Version
    private long version;

    protected VerifactuSecretDeletionJob() {
    }

    static VerifactuSecretDeletionJob pending(
            UUID companyId,
            UUID certificateId,
            String secretPath,
            VerifactuSecretDeletionReason reason,
            Instant createdAt) {
        var job = new VerifactuSecretDeletionJob();
        job.id = UUID.randomUUID();
        job.companyId = Objects.requireNonNull(companyId, "companyId");
        job.certificateId = certificateId;
        job.secretPath = required(secretPath, "secretPath");
        job.reason = Objects.requireNonNull(reason, "reason");
        job.status = VerifactuSecretDeletionStatus.PENDIENTE;
        job.nextAttemptAt = Objects.requireNonNull(createdAt, "createdAt");
        job.createdAt = createdAt;
        return job;
    }

    void claim(UUID owner, Instant now, Instant leaseUntil) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (!leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("El lease debe finalizar en el futuro");
        }
        boolean pendingAndDue = status == VerifactuSecretDeletionStatus.PENDIENTE
                && !nextAttemptAt.isAfter(now);
        boolean abandoned = status == VerifactuSecretDeletionStatus.PROCESANDO
                && !processingLeaseUntil.isAfter(now);
        if (!pendingAndDue && !abandoned) {
            throw new IllegalStateException("El borrado protegido no esta disponible");
        }
        status = VerifactuSecretDeletionStatus.PROCESANDO;
        attempts++;
        nextAttemptAt = null;
        processingOwner = owner;
        processingLeaseUntil = leaseUntil;
        lastError = null;
    }

    void complete(UUID owner, Instant completedAt) {
        requireOwner(owner);
        status = VerifactuSecretDeletionStatus.COMPLETADO;
        processingOwner = null;
        processingLeaseUntil = null;
        nextAttemptAt = null;
        lastError = null;
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
    }

    void retry(UUID owner, Instant nextAttemptAt, String errorCode) {
        requireOwner(owner);
        status = VerifactuSecretDeletionStatus.PENDIENTE;
        processingOwner = null;
        processingLeaseUntil = null;
        completedAt = null;
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        this.lastError = required(errorCode, "errorCode");
    }

    private void requireOwner(UUID owner) {
        if (status != VerifactuSecretDeletionStatus.PROCESANDO
                || !Objects.equals(processingOwner, owner)) {
            throw new IllegalStateException("El worker ya no posee el borrado protegido");
        }
    }

    UUID getId() {
        return id;
    }

    UUID getCompanyId() {
        return companyId;
    }

    UUID getCertificateId() {
        return certificateId;
    }

    String getSecretPath() {
        return secretPath;
    }

    VerifactuSecretDeletionReason getReason() {
        return reason;
    }

    VerifactuSecretDeletionStatus getStatus() {
        return status;
    }

    int getAttempts() {
        return attempts;
    }

    Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    UUID getProcessingOwner() {
        return processingOwner;
    }

    Instant getProcessingLeaseUntil() {
        return processingLeaseUntil;
    }

    String getLastError() {
        return lastError;
    }

    Instant getCompletedAt() {
        return completedAt;
    }

    boolean isOwnedBy(UUID owner) {
        return status == VerifactuSecretDeletionStatus.PROCESANDO
                && Objects.equals(processingOwner, owner);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}

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
@Table(name = "estado_envio_fiscal")
public class FiscalSubmissionState {

    @Id
    @Column(name = "registro_id")
    private UUID recordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 24)
    private FiscalSubmissionStatus status;

    @Column(name = "ultimo_error_codigo", length = 64)
    private String lastErrorCode;

    @Column(name = "ultimo_error")
    private String lastError;

    @Column(name = "actualizado_en", nullable = false)
    private Instant updatedAt;

    @Column(name = "intentos", nullable = false)
    private int attempts;

    @Column(name = "proximo_intento_en")
    private Instant nextAttemptAt;

    @Column(name = "lease_owner")
    private UUID leaseOwner;

    @Column(name = "lease_hasta")
    private Instant leaseUntil;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Version
    private long version;

    protected FiscalSubmissionState() {
    }

    FiscalSubmissionState(
            UUID recordId,
            FiscalSubmissionStatus status,
            Instant updatedAt) {
        this.recordId = Objects.requireNonNull(recordId, "recordId");
        this.status = Objects.requireNonNull(status, "status");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.nextAttemptAt = status == FiscalSubmissionStatus.PENDIENTE
                ? updatedAt : null;
    }

    public UUID getRecordId() {
        return recordId;
    }

    public FiscalSubmissionStatus getStatus() {
        return status;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public UUID getLeaseOwner() {
        return leaseOwner;
    }

    public Instant getClaimedAt() {
        return leaseUntil;
    }

    public UUID getClaimToken() {
        return claimToken;
    }

    /** Claims this row after it has been selected with a database row lock. */
    public void claim(UUID owner, UUID token, Instant now, Instant leaseUntil) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (!leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("El lease debe finalizar en el futuro");
        }
        boolean pending = status == FiscalSubmissionStatus.PENDIENTE && due(now);
        boolean retryable = status == FiscalSubmissionStatus.ENVIADO && due(now);
        boolean expired = status == FiscalSubmissionStatus.ENVIANDO
                && this.leaseUntil != null && !this.leaseUntil.isAfter(now);
        if (!pending && !retryable && !expired) {
            throw new IllegalStateException("El registro fiscal no esta disponible");
        }
        status = FiscalSubmissionStatus.ENVIANDO;
        attempts++;
        nextAttemptAt = null;
        leaseOwner = owner;
        this.leaseUntil = leaseUntil;
        claimToken = token;
        lastErrorCode = null;
        lastError = null;
        updatedAt = now;
    }

    /** Manual retries may reopen a retryable technical defect explicitly. */
    public void claimManual(UUID owner, UUID token, Instant now, Instant leaseUntil) {
        if (status != FiscalSubmissionStatus.ENVIADO
                && status != FiscalSubmissionStatus.DEFECTUOSO) {
            throw new IllegalStateException("El registro fiscal no admite reintento manual");
        }
        if (!leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("El lease debe finalizar en el futuro");
        }
        status = FiscalSubmissionStatus.ENVIANDO;
        attempts++;
        nextAttemptAt = null;
        leaseOwner = Objects.requireNonNull(owner, "owner");
        this.leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
        claimToken = Objects.requireNonNull(token, "token");
        lastErrorCode = null;
        lastError = null;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public boolean isOwnedBy(UUID token) {
        return status == FiscalSubmissionStatus.ENVIANDO
                && token != null && Objects.equals(claimToken, token)
                && leaseOwner != null
                && leaseUntil != null;
    }

    public boolean isOwnedBy(UUID token, Instant now) {
        return isOwnedBy(token) && now != null && leaseUntil.isAfter(now);
    }

    // Changes the state and clears errors when the incident is resolved.
    public void mark(FiscalSubmissionStatus newStatus, Instant changedAt) {
        status = Objects.requireNonNull(newStatus, "status");
        lastErrorCode = null;
        lastError = null;
        updatedAt = Objects.requireNonNull(changedAt, "changedAt");
        clearLease();
        nextAttemptAt = newStatus == FiscalSubmissionStatus.ENVIADO
                ? changedAt.plus(retryDelay(attempts)) : null;
    }

    // Records an incident visible for administrative review.
    public void markIncident(
            FiscalSubmissionStatus newStatus,
            String errorCode,
            String error,
            Instant changedAt) {
        status = Objects.requireNonNull(newStatus, "status");
        lastErrorCode = required(errorCode, "codigo de error");
        lastError = required(error, "error");
        updatedAt = Objects.requireNonNull(changedAt, "changedAt");
        clearLease();
        nextAttemptAt = null;
    }

    public void markWithClaim(
            FiscalSubmissionStatus newStatus,
            String errorCode,
            String error,
            Instant changedAt,
            UUID token) {
        requireClaim(token, changedAt);
        if (errorCode == null && error == null) {
            mark(newStatus, changedAt);
        } else {
            markIncident(newStatus, errorCode, error, changedAt);
        }
    }

    public void markRetryableFailureWithClaim(
            String errorCode, String error, Instant changedAt, UUID token) {
        requireClaim(token, changedAt);
        markIncident(FiscalSubmissionStatus.ENVIADO, errorCode, error, changedAt);
        nextAttemptAt = changedAt.plus(retryDelay(attempts));
    }

    private void requireClaim(UUID token, Instant now) {
        if (!isOwnedBy(token, now)) {
            throw new IllegalStateException("El worker ya no posee el registro fiscal");
        }
    }

    private boolean due(Instant now) {
        return nextAttemptAt == null || !nextAttemptAt.isAfter(now);
    }

    private void clearLease() {
        leaseOwner = null;
        leaseUntil = null;
        claimToken = null;
    }

    private static java.time.Duration retryDelay(int attempts) {
        return switch (Math.min(Math.max(attempts, 1), 5)) {
            case 1 -> java.time.Duration.ofMinutes(1);
            case 2 -> java.time.Duration.ofMinutes(5);
            case 3 -> java.time.Duration.ofMinutes(15);
            case 4 -> java.time.Duration.ofMinutes(30);
            default -> java.time.Duration.ofMinutes(60);
        };
    }

    private static String required(String value, String field) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return normalized;
    }
}

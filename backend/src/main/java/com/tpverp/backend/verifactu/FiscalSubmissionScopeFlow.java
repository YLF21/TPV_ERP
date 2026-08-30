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

/** Durable AEAT pacing and single-flight control for a company/install/env. */
@Entity
@Table(name = "flujo_envio_fiscal_scope")
public class FiscalSubmissionScopeFlow {
    @Id
    private UUID id;
    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;
    @Column(name = "instalacion_id", nullable = false)
    private UUID installationId;
    @Enumerated(EnumType.STRING)
    @Column(name = "entorno", nullable = false, length = 16)
    private FiscalEndpointEnvironment environment;
    @Column(name = "ultimo_envio_en")
    private Instant lastSubmissionAt;
    @Column(name = "siguiente_envio_en")
    private Instant nextAllowedAt;
    @Column(name = "espera_recibida_segundos")
    private Integer receivedWaitSeconds;
    @Column(name = "lease_owner")
    private UUID leaseOwner;
    @Column(name = "lease_hasta")
    private Instant leaseUntil;
    @Version
    private long version;

    protected FiscalSubmissionScopeFlow() {
    }

    public FiscalSubmissionScopeFlow(UUID companyId, UUID installationId,
            FiscalEndpointEnvironment environment) {
        this.id = UUID.randomUUID();
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.installationId = Objects.requireNonNull(installationId, "installationId");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getInstallationId() { return installationId; }
    public FiscalEndpointEnvironment getEnvironment() { return environment; }
    public Instant getLastSubmissionAt() { return lastSubmissionAt; }
    public Instant getNextAllowedAt() { return nextAllowedAt; }
    public Integer getReceivedWaitSeconds() { return receivedWaitSeconds; }
    public UUID getLeaseOwner() { return leaseOwner; }
    public Instant getLeaseUntil() { return leaseUntil; }

    public boolean available(Instant now) {
        return leaseUntil == null || !leaseUntil.isAfter(now);
    }

    public boolean isOwnedBy(UUID owner, Instant now) {
        return owner != null && Objects.equals(leaseOwner, owner)
                && leaseUntil != null && now != null && leaseUntil.isAfter(now);
    }

    public void claim(UUID owner, Instant now, Instant until) {
        if (!available(now)) throw new IllegalStateException("scope fiscal ocupado");
        leaseOwner = Objects.requireNonNull(owner, "owner");
        leaseUntil = Objects.requireNonNull(until, "until");
        if (!until.isAfter(now)) throw new IllegalArgumentException("lease invalido");
    }

    public void completed(Instant sentAt, int waitSeconds) {
        if (waitSeconds < 0 || waitSeconds > 9999) {
            throw new IllegalArgumentException("TiempoEsperaEnvio fuera de rango");
        }
        lastSubmissionAt = Objects.requireNonNull(sentAt, "sentAt");
        receivedWaitSeconds = waitSeconds;
        nextAllowedAt = sentAt.plusSeconds(waitSeconds);
        leaseOwner = null;
        leaseUntil = null;
    }

    public void completed(UUID owner, Instant sentAt, int waitSeconds) {
        if (!isOwnedBy(owner, sentAt)) throw new IllegalStateException("El worker ya no posee el scope fiscal");
        completed(sentAt, waitSeconds);
    }

    public void release() {
        leaseOwner = null;
        leaseUntil = null;
    }

    public boolean release(UUID owner, Instant now) {
        if (!isOwnedBy(owner, now)) return false;
        release();
        return true;
    }
}

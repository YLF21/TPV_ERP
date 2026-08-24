package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Persisted operating-time accumulator used by the NO VERI*FACTU event summary.
 * A long observation gap is treated as downtime and is never counted.
 */
@Entity
@Table(name = "reloj_operativo_fiscal")
public class FiscalOperatingClock {
    @Id
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;

    @Column(name = "instalacion_id", nullable = false)
    private UUID installationId;

    @Column(name = "observado_en", nullable = false)
    private Instant observedAt;

    @Column(name = "segundos_desde_resumen", nullable = false)
    private long secondsSinceSummary;

    @Version
    private long version;

    protected FiscalOperatingClock() {
    }

    public FiscalOperatingClock(UUID companyId, UUID installationId, Instant observedAt) {
        id = UUID.randomUUID();
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.installationId = Objects.requireNonNull(installationId, "installationId");
        this.observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }

    /**
     * Adds only the interval for which the application kept reporting heartbeats.
     * The caller supplies a small maximum gap matching the scheduler cadence.
     */
    public void observe(Instant now, Duration maximumGap) {
        var current = Objects.requireNonNull(now, "now");
        var gap = Objects.requireNonNull(maximumGap, "maximumGap");
        if (gap.isNegative() || gap.isZero()) {
            throw new IllegalArgumentException("maximumGap debe ser positivo");
        }
        if (current.isBefore(observedAt)) {
            throw new IllegalArgumentException("El reloj operativo no puede retroceder");
        }
        var elapsed = Duration.between(observedAt, current);
        if (elapsed.compareTo(gap) <= 0) {
            secondsSinceSummary = Math.addExact(secondsSinceSummary, elapsed.toSeconds());
        }
        observedAt = current;
    }

    public boolean isDue(Duration threshold) {
        var value = Objects.requireNonNull(threshold, "threshold");
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("threshold debe ser positivo");
        }
        return secondsSinceSummary >= value.toSeconds();
    }

    public void reset(Instant now) {
        observedAt = Objects.requireNonNull(now, "now");
        secondsSinceSummary = 0;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getInstallationId() {
        return installationId;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public long getSecondsSinceSummary() {
        return secondsSinceSummary;
    }
}

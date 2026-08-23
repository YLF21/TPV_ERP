package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "configuracion_verifactu")
public class VerifactuConfiguration {

    @Id
    private UUID id;

    @Column(name = "empresa_id", nullable = false, unique = true)
    private UUID companyId;

    @Column(name = "activacion_voluntaria", nullable = false)
    private boolean voluntarilyActive;

    @Column(name = "activada_en")
    private Instant activatedAt;

    @Column(name = "primera_remision_en")
    private Instant firstSubmissionAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "modo_actual", nullable = false, length = 16)
    private FiscalMode currentMode = FiscalMode.PRE_SIF;

    @Column(name = "modo_desde")
    private Instant modeSince;

    @Column(name = "verifactu_bloqueado_hasta")
    private LocalDate verifactuBlockedUntil;

    @Column(name = "modo_version", nullable = false)
    private long modeVersion;

    @Version
    private long version;

    protected VerifactuConfiguration() {
    }

    public VerifactuConfiguration(UUID companyId) {
        id = UUID.randomUUID();
        this.companyId = Objects.requireNonNull(companyId, "companyId");
    }

    // Activa voluntariamente VERI*FACTU y conserva el instante efectivo.
    public void activateVoluntarily(Instant activatedAt) {
        var value = Objects.requireNonNull(activatedAt, "activatedAt");
        if (voluntarilyActive) {
            return;
        }
        if (firstSubmissionAt != null) {
            throw new IllegalStateException("VERI*FACTU ya no puede cambiar de modo");
        }
        voluntarilyActive = true;
        this.activatedAt = value;
    }

    // Records a voluntary submission or the exact legal activation instant.
    public void markFirstSubmission(Instant submittedAt, Instant legalActivationAt) {
        if (!voluntarilyActive && legalActivationAt == null) {
            throw new IllegalStateException("VERI*FACTU debe estar activo");
        }
        if (firstSubmissionAt != null) {
            throw new IllegalStateException("message.verifactu.first_submission_already_registered");
        }
        var value = Objects.requireNonNull(submittedAt, "submittedAt");
        var effectiveActivation = activatedAt == null ? legalActivationAt : activatedAt;
        if (value.isBefore(effectiveActivation)) {
            throw new IllegalArgumentException("message.verifactu.submission_before_activation");
        }
        if (activatedAt == null) {
            activatedAt = legalActivationAt;
        }
        firstSubmissionAt = value;
    }

    // Disables voluntary mode only before any submission.
    public void deactivateVoluntarily() {
        if (firstSubmissionAt != null) {
            throw new IllegalStateException("message.verifactu.first_submission_already_done");
        }
        voluntarilyActive = false;
        activatedAt = null;
    }

    public boolean isVoluntarilyActive() {
        return voluntarilyActive;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public FiscalMode getCurrentMode() {
        return currentMode;
    }

    public Instant getModeSince() {
        return modeSince;
    }

    public LocalDate getVerifactuBlockedUntil() {
        return verifactuBlockedUntil;
    }

    /**
     * Freezes the annual VERI*FACTU permanence window without shortening an
     * already persisted legal lock.
     */
    public void lockVerifactuUntil(LocalDate until) {
        if (until != null && (verifactuBlockedUntil == null
                || until.isAfter(verifactuBlockedUntil))) {
            verifactuBlockedUntil = until;
        }
    }

    public long getModeVersion() {
        return modeVersion;
    }

    public void changeMode(FiscalMode target, Instant effectiveAt, LocalDate blockedUntil) {
        var next = Objects.requireNonNull(target, "target");
        if (next == currentMode) {
            return;
        }
        currentMode = next;
        modeSince = Objects.requireNonNull(effectiveAt, "effectiveAt");
        verifactuBlockedUntil = blockedUntil;
        modeVersion++;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public Instant getFirstSubmissionAt() {
        return firstSubmissionAt;
    }
}

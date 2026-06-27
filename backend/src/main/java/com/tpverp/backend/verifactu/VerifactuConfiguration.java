package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
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

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public Instant getFirstSubmissionAt() {
        return firstSubmissionAt;
    }
}

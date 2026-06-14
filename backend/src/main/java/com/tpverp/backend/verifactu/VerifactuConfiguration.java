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
        if (firstSubmissionAt != null) {
            throw new IllegalStateException("VERI*FACTU ya no puede cambiar de modo");
        }
        voluntarilyActive = true;
        this.activatedAt = Objects.requireNonNull(activatedAt, "activatedAt");
    }

    // Registra una sola vez la primera remisión, que vuelve irreversible la activación.
    public void markFirstSubmission(Instant submittedAt) {
        if (!voluntarilyActive || activatedAt == null) {
            throw new IllegalStateException("VERI*FACTU debe estar activo");
        }
        if (firstSubmissionAt != null) {
            throw new IllegalStateException("La primera remisión ya está registrada");
        }
        var value = Objects.requireNonNull(submittedAt, "submittedAt");
        if (value.isBefore(activatedAt)) {
            throw new IllegalArgumentException("La remisión no puede preceder a la activación");
        }
        firstSubmissionAt = value;
    }

    // Desactiva el modo voluntario únicamente antes de cualquier remisión.
    public void deactivateVoluntarily() {
        if (firstSubmissionAt != null) {
            throw new IllegalStateException("VERI*FACTU ya realizó su primera remisión");
        }
        voluntarilyActive = false;
        activatedAt = null;
    }

    public boolean isVoluntarilyActive() {
        return voluntarilyActive;
    }

    public Instant getFirstSubmissionAt() {
        return firstSubmissionAt;
    }
}

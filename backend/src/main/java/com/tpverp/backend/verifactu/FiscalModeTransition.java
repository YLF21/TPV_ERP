package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "transicion_modo_fiscal")
public class FiscalModeTransition {
    @Id private UUID id;
    @Column(name = "empresa_id", nullable = false) private UUID companyId;
    @Column(name = "instalacion_id", nullable = false) private UUID installationId;
    @Enumerated(EnumType.STRING) @Column(name = "modo_anterior", nullable = false, length = 16)
    private FiscalMode previousMode;
    @Enumerated(EnumType.STRING) @Column(name = "modo_nuevo", nullable = false, length = 16)
    private FiscalMode newMode;
    @Column(name = "solicitada_en", nullable = false) private Instant requestedAt;
    @Column(name = "efectiva_en", nullable = false) private Instant effectiveAt;
    @Column(nullable = false, length = 32) private String causa;
    @Column(nullable = false, columnDefinition = "text") private String motivo;
    @Column(name = "expected_version", nullable = false) private long expectedVersion;

    protected FiscalModeTransition() {}

    public FiscalModeTransition(UUID companyId, UUID installationId, FiscalMode previousMode,
            FiscalMode newMode, Instant requestedAt, String cause, String reason,
            long expectedVersion) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.installationId = installationId;
        this.previousMode = previousMode;
        this.newMode = newMode;
        this.requestedAt = requestedAt;
        this.effectiveAt = requestedAt;
        this.causa = cause;
        this.motivo = reason;
        this.expectedVersion = expectedVersion;
    }

    public UUID getId() { return id; }
    public FiscalMode getPreviousMode() { return previousMode; }
    public FiscalMode getNewMode() { return newMode; }
    public Instant getRequestedAt() { return requestedAt; }
    public String getReason() { return motivo; }
}

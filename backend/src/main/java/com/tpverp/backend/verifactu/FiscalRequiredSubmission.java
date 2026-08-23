package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "requerimiento_fiscal")
public class FiscalRequiredSubmission {
    @Id
    private UUID id;
    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;
    @Column(name = "instalacion_id", nullable = false)
    private UUID installationId;
    @Column(name = "referencia", nullable = false, length = 100)
    private String reference;
    @Column(name = "solicitado_en", nullable = false)
    private Instant requestedAt;
    @Column(name = "estado", nullable = false, length = 16)
    private String status;

    protected FiscalRequiredSubmission() {}

    public FiscalRequiredSubmission(UUID companyId, UUID installationId, String reference,
            Instant requestedAt) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.installationId = installationId;
        this.reference = reference;
        this.requestedAt = requestedAt;
        this.status = "PENDIENTE";
    }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public Instant getRequestedAt() { return requestedAt; }
    public String getStatus() { return status; }
}

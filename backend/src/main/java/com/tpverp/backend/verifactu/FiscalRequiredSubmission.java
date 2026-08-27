package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.OffsetDateTime;
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
    @Column(name = "referencia", nullable = false, length = 18)
    private String reference;
    @Column(name = "solicitado_en", nullable = false)
    private Instant requestedAt;
    @Column(name = "atendido_en")
    private Instant attendedAt;
    @Column(name = "exportacion_id")
    private UUID exportId;
    @Column(name = "estado", nullable = false, length = 16)
    private String status;
    @Column(name = "periodo_inicio")
    private OffsetDateTime periodStart;
    @Column(name = "periodo_fin")
    private OffsetDateTime periodEnd;

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
    public UUID getCompanyId() { return companyId; }
    public UUID getInstallationId() { return installationId; }
    public String getReference() { return reference; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getAttendedAt() { return attendedAt; }
    public UUID getExportId() { return exportId; }
    public String getStatus() { return status; }
    public OffsetDateTime getPeriodStart() { return periodStart; }
    public OffsetDateTime getPeriodEnd() { return periodEnd; }

    /** Freezes the AEAT requested period on the first durable export attempt. */
    public void freezePeriod(OffsetDateTime start, OffsetDateTime end) {
        if (start == null || end == null || end.isBefore(start)) {
            throw new IllegalArgumentException("El periodo del requerimiento no es valido");
        }
        if (periodStart == null && periodEnd == null) {
            periodStart = start;
            periodEnd = end;
            return;
        }
        if (!start.equals(periodStart) || !end.equals(periodEnd)) {
            throw new IllegalStateException("fiscal_required_submission_period_mismatch");
        }
    }

    public void markExported(UUID exportId, Instant attendedAt) {
        if (!"PENDIENTE".equals(status)) {
            throw new IllegalStateException("El requerimiento fiscal ya esta cerrado");
        }
        this.exportId = exportId;
        this.attendedAt = attendedAt;
        this.status = "EXPORTADO";
    }

    public void markError() {
        if (!"PENDIENTE".equals(status)) {
            throw new IllegalStateException("El requerimiento fiscal ya esta cerrado");
        }
        this.status = "ERROR";
    }
}

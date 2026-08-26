package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "trabajo_integridad_fiscal")
public class FiscalIntegrityJob {
    @Id
    private UUID id;
    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;
    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;
    @Column(name = "instalacion_id", nullable = false)
    private UUID installationId;
    @Column(name = "solicitado_por", nullable = false, length = 128)
    private String requestedBy;
    @Enumerated(EnumType.STRING)
    @Column(name = "modo_ejecucion", nullable = false, length = 16)
    private FiscalMode executionMode;
    @Column(name = "secuencia_facturacion_corte", nullable = false)
    private long billingSnapshotSequence;
    @Column(name = "secuencia_eventos_corte", nullable = false)
    private long eventSnapshotSequence;
    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 16)
    private FiscalIntegrityJobStatus status;
    @Column(name = "token_ejecucion")
    private UUID executionToken;
    @Column(name = "facturacion_comprobada", nullable = false)
    private long billingChecked;
    @Column(name = "eventos_comprobados", nullable = false)
    private long eventsChecked;
    @Column(name = "anomalias_total", nullable = false)
    private long anomaliesTotal;
    @Column(name = "anomalias_facturacion", nullable = false)
    private long billingAnomalies;
    @Column(name = "anomalias_eventos", nullable = false)
    private long eventAnomalies;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidencia_codigos", nullable = false, columnDefinition = "jsonb")
    private List<String> evidenceCodes;
    @Column(name = "error", columnDefinition = "text")
    private String error;
    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;
    @Column(name = "iniciado_en")
    private Instant startedAt;
    @Column(name = "actualizado_en", nullable = false)
    private Instant updatedAt;
    @Column(name = "completado_en")
    private Instant completedAt;
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected FiscalIntegrityJob() {}

    public FiscalIntegrityJob(UUID companyId, UUID storeId, UUID installationId,
            String requestedBy, FiscalMode mode, long billingSnapshotSequence,
            long eventSnapshotSequence, Instant now) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.storeId = storeId;
        this.installationId = installationId;
        this.requestedBy = requestedBy;
        this.executionMode = mode;
        this.billingSnapshotSequence = billingSnapshotSequence;
        this.eventSnapshotSequence = eventSnapshotSequence;
        this.status = FiscalIntegrityJobStatus.QUEUED;
        this.evidenceCodes = List.of();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getStoreId() { return storeId; }
    public UUID getInstallationId() { return installationId; }
    public String getRequestedBy() { return requestedBy; }
    public FiscalMode getExecutionMode() { return executionMode; }
    public long getBillingSnapshotSequence() { return billingSnapshotSequence; }
    public long getEventSnapshotSequence() { return eventSnapshotSequence; }
    public FiscalIntegrityJobStatus getStatus() { return status; }
    public UUID getExecutionToken() { return executionToken; }
    public long getBillingChecked() { return billingChecked; }
    public long getEventsChecked() { return eventsChecked; }
    public long getAnomaliesTotal() { return anomaliesTotal; }
    public long getBillingAnomalies() { return billingAnomalies; }
    public long getEventAnomalies() { return eventAnomalies; }
    public List<String> getEvidenceCodes() { return evidenceCodes == null ? List.of() : List.copyOf(evidenceCodes); }
    public String getError() { return error; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }

    public void markRunning(Instant now) {
        status = FiscalIntegrityJobStatus.RUNNING;
        startedAt = startedAt == null ? now : startedAt;
        updatedAt = now;
    }

    public void markProgress(long billingChecked, long eventsChecked,
            long anomaliesTotal, long billingAnomalies, long eventAnomalies,
            List<String> evidenceCodes, Instant now) {
        this.billingChecked = billingChecked;
        this.eventsChecked = eventsChecked;
        this.anomaliesTotal = anomaliesTotal;
        this.billingAnomalies = billingAnomalies;
        this.eventAnomalies = eventAnomalies;
        this.evidenceCodes = evidenceCodes == null ? List.of() : List.copyOf(evidenceCodes);
        this.updatedAt = now;
    }

    public void markCompleted(Instant now) {
        this.status = FiscalIntegrityJobStatus.COMPLETED;
        this.completedAt = now;
        this.updatedAt = now;
    }

    public void markFailed(String error, Instant now) {
        this.error = error == null || error.isBlank() ? "fiscal_integrity_failed" : error;
        this.status = FiscalIntegrityJobStatus.FAILED;
        this.updatedAt = now;
    }
}

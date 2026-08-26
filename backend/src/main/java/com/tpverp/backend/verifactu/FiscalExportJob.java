package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Version;

@Entity
@Table(name = "trabajo_exportacion_fiscal")
public class FiscalExportJob {
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
    @Column(name = "tipo", nullable = false, length = 16)
    private FiscalExportKind kind;
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 16)
    private FiscalExportJobScope scope;
    @Column(name = "requerimiento_id")
    private UUID requiredSubmissionId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "record_ids", nullable = false, columnDefinition = "jsonb")
    private List<UUID> recordIds;
    @Column(name = "fecha_inicio")
    private OffsetDateTime periodStart;
    @Column(name = "fecha_fin")
    private OffsetDateTime periodEnd;
    @Column(name = "fecha_expedicion_desde")
    private LocalDate dateFrom;
    @Column(name = "fecha_expedicion_hasta")
    private LocalDate dateTo;
    @Column(name = "numero_documento", length = 64)
    private String documentNumber;
    @Column(name = "prefijo_documento", length = 64)
    private String documentNumberPrefix;
    @Enumerated(EnumType.STRING)
    @Column(name = "operacion", length = 16)
    private FiscalRecordOperation operation;
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_documento_fiscal", length = 4)
    private FiscalDocumentType documentType;
    @Enumerated(EnumType.STRING)
    @Column(name = "modo_fiscal", length = 16)
    private FiscalMode fiscalMode;
    @Enumerated(EnumType.STRING)
    @Column(name = "modo_ejecucion", nullable = false, length = 16)
    private FiscalMode executionMode;
    @Column(name = "secuencia_corte", nullable = false)
    private long snapshotSequence;
    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 16)
    private FiscalExportJobStatus status;
    @Column(name = "procesados", nullable = false)
    private long processed;
    @Column(name = "hay_mas", nullable = false)
    private boolean hasMore;
    @Column(name = "error")
    private String error;
    @Column(name = "ruta_fichero")
    private String filePath;
    @Column(name = "tamano_fichero", nullable = false)
    private long fileSize;
    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;
    @Column(name = "iniciado_en")
    private Instant startedAt;
    @Column(name = "actualizado_en", nullable = false)
    private Instant updatedAt;
    @Column(name = "completado_en")
    private Instant completedAt;
    @Column(name = "expira_en", nullable = false)
    private Instant expiresAt;
    @Column(name = "token_ejecucion")
    private UUID executionToken;
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected FiscalExportJob() {}

    public FiscalExportJob(UUID companyId, UUID storeId, UUID installationId, String requestedBy,
            FiscalExportJobRequest request, FiscalMode executionMode, long snapshotSequence,
            Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.storeId = storeId;
        this.installationId = installationId;
        this.requestedBy = requestedBy;
        this.kind = request.kind();
        this.scope = request.scope();
        this.recordIds = List.copyOf(request.safeRecordIds());
        this.periodStart = request.periodStart();
        this.periodEnd = request.periodEnd();
        this.dateFrom = request.dateFrom();
        this.dateTo = request.dateTo();
        this.documentNumber = request.documentNumber();
        this.documentNumberPrefix = request.documentNumberPrefix();
        this.operation = request.operation();
        this.documentType = request.documentType();
        this.fiscalMode = request.fiscalMode();
        this.executionMode = executionMode;
        this.snapshotSequence = snapshotSequence;
        this.status = FiscalExportJobStatus.QUEUED;
        this.createdAt = now;
        this.updatedAt = now;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getStoreId() { return storeId; }
    public UUID getInstallationId() { return installationId; }
    public String getRequestedBy() { return requestedBy; }
    public FiscalExportKind getKind() { return kind; }
    public FiscalExportJobScope getScope() { return scope; }
    public UUID getRequiredSubmissionId() { return requiredSubmissionId; }
    public void attachRequiredSubmission(UUID id) { this.requiredSubmissionId = id; }
    public List<UUID> getRecordIds() { return recordIds == null ? List.of() : List.copyOf(recordIds); }
    public OffsetDateTime getPeriodStart() { return periodStart; }
    public OffsetDateTime getPeriodEnd() { return periodEnd; }
    public LocalDate getDateFrom() { return dateFrom; }
    public LocalDate getDateTo() { return dateTo; }
    public String getDocumentNumber() { return documentNumber; }
    public String getDocumentNumberPrefix() { return documentNumberPrefix; }
    public FiscalRecordOperation getOperation() { return operation; }
    public FiscalDocumentType getDocumentType() { return documentType; }
    public FiscalMode getFiscalMode() { return fiscalMode; }
    public FiscalMode getExecutionMode() { return executionMode; }
    public long getSnapshotSequence() { return snapshotSequence; }
    public FiscalExportJobStatus getStatus() { return status; }
    public long getProcessed() { return processed; }
    public boolean isHasMore() { return hasMore; }
    public String getError() { return error; }
    public String getFilePath() { return filePath; }
    public long getFileSize() { return fileSize; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public UUID getExecutionToken() { return executionToken; }

    void adoptExecutionToken(UUID token) {
        if (token == null) return;
        if (executionToken != null && !executionToken.equals(token)) {
            throw new IllegalStateException("fiscal_export_claim_lost");
        }
        executionToken = token;
    }

    public void markRunning(Instant now) {
        status = FiscalExportJobStatus.RUNNING;
        startedAt = startedAt == null ? now : startedAt;
        updatedAt = now;
    }

    public void markProgress(long processed, boolean hasMore, Instant now) {
        this.processed = processed;
        this.hasMore = hasMore;
        this.updatedAt = now;
    }

    public void markCompleted(String filePath, long fileSize, long processed,
            boolean hasMore, Instant now) {
        markCompleted(filePath, fileSize, processed, hasMore, now, expiresAt);
    }

    public void markCompleted(String filePath, long fileSize, long processed,
            boolean hasMore, Instant now, Instant retentionExpiresAt) {
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.processed = processed;
        this.hasMore = hasMore;
        this.status = FiscalExportJobStatus.COMPLETED;
        this.executionToken = null;
        this.completedAt = now;
        this.expiresAt = retentionExpiresAt;
        this.updatedAt = now;
    }

    public void markFailed(String error, Instant now) {
        this.error = error == null || error.isBlank() ? "fiscal_export_failed" : error;
        this.status = FiscalExportJobStatus.FAILED;
        this.executionToken = null;
        this.updatedAt = now;
    }

    public void markExpired(Instant now) {
        this.status = FiscalExportJobStatus.EXPIRED;
        this.executionToken = null;
        this.updatedAt = now;
    }
}

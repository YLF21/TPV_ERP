package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "exportacion_fiscal")
@Immutable
public class FiscalExport {
    @Id
    private UUID id;
    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;
    @Column(name = "instalacion_id", nullable = false)
    private UUID installationId;
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 16)
    private FiscalExportKind kind;
    @Column(name = "evento_id")
    private UUID eventId;
    @Column(name = "numero_registros", nullable = false)
    private long recordCount;
    @Column(name = "contenido_hash", nullable = false, length = 64)
    private String contentHash;
    @Column(name = "exportada_en", nullable = false)
    private Instant exportedAt;
    @Column(name = "periodo_inicio")
    private OffsetDateTime periodStart;
    @Column(name = "periodo_fin")
    private OffsetDateTime periodEnd;

    protected FiscalExport() {}

    public FiscalExport(UUID companyId, UUID installationId, FiscalExportKind kind,
            UUID eventId, long recordCount, String contentHash, Instant exportedAt) {
        this(companyId, installationId, kind, eventId, recordCount, contentHash, exportedAt,
                null, null);
    }

    public FiscalExport(UUID companyId, UUID installationId, FiscalExportKind kind,
            UUID eventId, long recordCount, String contentHash, Instant exportedAt,
            OffsetDateTime periodStart, OffsetDateTime periodEnd) {
        this(UUID.randomUUID(), companyId, installationId, kind, eventId, recordCount,
                contentHash, exportedAt, periodStart, periodEnd);
    }

    /** Constructor used by streaming downloads so the manifest can carry the persisted id. */
    public FiscalExport(UUID id, UUID companyId, UUID installationId, FiscalExportKind kind,
            UUID eventId, long recordCount, String contentHash, Instant exportedAt,
            OffsetDateTime periodStart, OffsetDateTime periodEnd) {
        this.id = id;
        this.companyId = companyId;
        this.installationId = installationId;
        this.kind = kind;
        this.eventId = eventId;
        this.recordCount = recordCount;
        this.contentHash = contentHash;
        this.exportedAt = exportedAt;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getInstallationId() { return installationId; }
    public FiscalExportKind getKind() { return kind; }
    public UUID getEventId() { return eventId; }
    public long getRecordCount() { return recordCount; }
    public String getContentHash() { return contentHash; }
    public Instant getExportedAt() { return exportedAt; }
    public OffsetDateTime getPeriodStart() { return periodStart; }
    public OffsetDateTime getPeriodEnd() { return periodEnd; }
}

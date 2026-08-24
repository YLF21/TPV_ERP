package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "exportacion_fiscal")
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
        this.id = UUID.randomUUID();
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
    public UUID getEventId() { return eventId; }
    public long getRecordCount() { return recordCount; }
    public String getContentHash() { return contentHash; }
    public Instant getExportedAt() { return exportedAt; }
    public OffsetDateTime getPeriodStart() { return periodStart; }
    public OffsetDateTime getPeriodEnd() { return periodEnd; }
}

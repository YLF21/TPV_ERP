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
@Table(name = "snapshot_impresion_fiscal")
public class FiscalPrintSnapshotRecord {
    @Id @Column(name = "registro_id") private UUID recordId;
    @Enumerated(EnumType.STRING) @Column(name = "modo_fiscal", nullable = false, length = 16)
    private FiscalMode mode;
    @Enumerated(EnumType.STRING) @Column(name = "entorno", nullable = false, length = 16)
    private FiscalEndpointEnvironment environment;
    @Column(name = "version_formato", nullable = false, length = 16) private String formatVersion;
    @Column(name = "generador_version", nullable = false, length = 64) private String generatorVersion;
    @Column(name = "qr_url", nullable = false, columnDefinition = "text") private String qrUrl;
    @Column(name = "qr_hash", nullable = false, length = 64) private String qrHash;
    @Column(name = "qr_prefijo", nullable = false, length = 64) private String prefix;
    @Column(name = "qr_leyenda", columnDefinition = "text") private String legend;
    @Column(name = "aviso_pruebas", columnDefinition = "text") private String testNotice;
    @Column(name = "creado_en", nullable = false) private Instant createdAt;

    protected FiscalPrintSnapshotRecord() {}

    public FiscalPrintSnapshotRecord(UUID recordId, FiscalPrintSnapshot snapshot, Instant createdAt) {
        this.recordId = recordId;
        this.mode = snapshot.mode();
        this.environment = snapshot.environment();
        this.formatVersion = snapshot.formatVersion();
        this.generatorVersion = snapshot.generatorVersion();
        this.qrUrl = snapshot.qrUrl();
        this.qrHash = snapshot.qrPayloadSha256();
        this.prefix = snapshot.prefix();
        this.legend = snapshot.legend();
        this.testNotice = snapshot.testNotice();
        this.createdAt = createdAt;
    }

    public UUID getRecordId() { return recordId; }
    public FiscalMode getMode() { return mode; }
    public FiscalEndpointEnvironment getEnvironment() { return environment; }
    public String getFormatVersion() { return formatVersion; }
    public String getGeneratorVersion() { return generatorVersion; }
    public String getQrUrl() { return qrUrl; }
    public String getQrHash() { return qrHash; }
    public String getPrefix() { return prefix; }
    public String getLegend() { return legend; }
    public String getTestNotice() { return testNotice; }
    public Instant getCreatedAt() { return createdAt; }
}

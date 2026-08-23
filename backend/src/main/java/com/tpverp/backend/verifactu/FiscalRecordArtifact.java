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

/** Immutable XML and print evidence attached to exactly one fiscal record. */
@Entity
@Immutable
@Table(name = "artefacto_registro_fiscal")
public class FiscalRecordArtifact {

    @Id
    @Column(name = "registro_id")
    private UUID recordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "modo_fiscal", nullable = false, length = 16)
    private FiscalMode fiscalMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "entorno", nullable = false, length = 16)
    private FiscalEndpointEnvironment environment;

    @Column(name = "sandbox", nullable = false)
    private boolean sandbox;

    @Column(name = "version_sistema_id")
    private UUID systemVersionId;

    @Column(name = "xml_sin_firmar", nullable = false, columnDefinition = "text")
    private String unsignedXml;

    @Column(name = "xml_firmado", columnDefinition = "text")
    private String signedXml;

    @Column(name = "xml_hash", nullable = false, length = 64)
    private String xmlHash;

    @Column(name = "qr_url", nullable = false, columnDefinition = "text")
    private String qrUrl;

    @Column(name = "qr_hash", nullable = false, length = 64)
    private String qrHash;

    @Column(name = "qr_prefijo", nullable = false, length = 64)
    private String qrPrefix;

    @Column(name = "qr_leyenda", columnDefinition = "text")
    private String qrLegend;

    @Column(name = "aviso_pruebas", columnDefinition = "text")
    private String testNotice;

    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;

    protected FiscalRecordArtifact() {
    }

    public FiscalRecordArtifact(
            UUID recordId,
            FiscalMode fiscalMode,
            FiscalEndpointEnvironment environment,
            boolean sandbox,
            UUID systemVersionId,
            String unsignedXml,
            String signedXml,
            String xmlHash,
            FiscalPrintSnapshot printSnapshot,
            Instant createdAt) {
        this.recordId = recordId;
        this.fiscalMode = fiscalMode;
        this.environment = environment;
        this.sandbox = sandbox;
        this.systemVersionId = systemVersionId;
        this.unsignedXml = unsignedXml;
        this.signedXml = signedXml;
        this.xmlHash = xmlHash;
        this.qrUrl = printSnapshot.qrUrl();
        this.qrHash = printSnapshot.qrPayloadSha256();
        this.qrPrefix = printSnapshot.prefix();
        this.qrLegend = printSnapshot.legend();
        this.testNotice = printSnapshot.testNotice();
        this.createdAt = createdAt;
    }

    public FiscalRecordArtifact(
            UUID recordId,
            FiscalMode fiscalMode,
            FiscalEndpointEnvironment environment,
            boolean sandbox,
            String unsignedXml,
            String signedXml,
            String xmlHash,
            FiscalPrintSnapshot printSnapshot,
            Instant createdAt) {
        this(recordId, fiscalMode, environment, sandbox, null, unsignedXml, signedXml,
                xmlHash, printSnapshot, createdAt);
    }

    public UUID getRecordId() { return recordId; }
    public FiscalMode getFiscalMode() { return fiscalMode; }
    public FiscalEndpointEnvironment getEnvironment() { return environment; }
    public boolean isSandbox() { return sandbox; }
    public UUID getSystemVersionId() { return systemVersionId; }
    public String getUnsignedXml() { return unsignedXml; }
    public String getSignedXml() { return signedXml; }
    public String getQrUrl() { return qrUrl; }
    public String getQrHash() { return qrHash; }
    public String getQrPrefix() { return qrPrefix; }
    public String getQrLegend() { return qrLegend; }
    public String getTestNotice() { return testNotice; }
    public Instant getCreatedAt() { return createdAt; }
}

package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "registro_evento_fiscal")
public class FiscalEvent {
    @Id
    private UUID id;
    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;
    @Column(name = "instalacion_id", nullable = false)
    private UUID installationId;
    @Column(name = "version_sistema_id")
    private UUID systemVersionId;
    @Column(name = "secuencia", nullable = false)
    private long sequence;
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_evento", nullable = false, length = 2)
    private FiscalEventType type;
    @Enumerated(EnumType.STRING)
    @Column(name = "modo_fiscal", nullable = false, length = 16)
    private FiscalMode fiscalMode;
    @Column(name = "generado_en", nullable = false)
    private Instant generatedAt;
    @Column(name = "huella_evento_anterior", length = 64)
    private String previousHash;
    @Column(name = "huella_evento", nullable = false, length = 64)
    private String hash;
    @Column(name = "xml_sin_firmar", nullable = false, columnDefinition = "text")
    private String unsignedXml;
    @Column(name = "xml_firmado", nullable = false, columnDefinition = "text")
    private String signedXml;
    @Column(name = "xml_hash", nullable = false, length = 64)
    private String xmlHash;
    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;

    protected FiscalEvent() {}

    public FiscalEvent(UUID companyId, UUID installationId, UUID systemVersionId, long sequence,
            FiscalEventType type,
            FiscalMode fiscalMode, Instant generatedAt, String previousHash, String hash,
            String unsignedXml, String signedXml, String xmlHash, Instant createdAt) {
        id = UUID.randomUUID();
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.installationId = Objects.requireNonNull(installationId, "installationId");
        this.systemVersionId = systemVersionId;
        this.sequence = sequence;
        this.type = Objects.requireNonNull(type, "type");
        this.fiscalMode = Objects.requireNonNull(fiscalMode, "fiscalMode");
        this.generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
        this.previousHash = previousHash;
        this.hash = Objects.requireNonNull(hash, "hash");
        this.unsignedXml = Objects.requireNonNull(unsignedXml, "unsignedXml");
        this.signedXml = Objects.requireNonNull(signedXml, "signedXml");
        this.xmlHash = Objects.requireNonNull(xmlHash, "xmlHash");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Compatibility constructor for legacy event fixtures without a frozen SIF identity. */
    public FiscalEvent(UUID companyId, UUID installationId, long sequence, FiscalEventType type,
            FiscalMode fiscalMode, Instant generatedAt, String previousHash, String hash,
            String unsignedXml, String signedXml, String xmlHash, Instant createdAt) {
        this(companyId, installationId, null, sequence, type, fiscalMode, generatedAt,
                previousHash, hash, unsignedXml, signedXml, xmlHash, createdAt);
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getInstallationId() { return installationId; }
    public UUID getSystemVersionId() { return systemVersionId; }
    public long getSequence() { return sequence; }
    public FiscalEventType getType() { return type; }
    public FiscalMode getFiscalMode() { return fiscalMode; }
    public Instant getGeneratedAt() { return generatedAt; }
    public String getPreviousHash() { return previousHash; }
    public String getHash() { return hash; }
    public String getUnsignedXml() { return unsignedXml; }
    public String getSignedXml() { return signedXml; }
    public String getXmlHash() { return xmlHash; }
    public Instant getCreatedAt() { return createdAt; }
}

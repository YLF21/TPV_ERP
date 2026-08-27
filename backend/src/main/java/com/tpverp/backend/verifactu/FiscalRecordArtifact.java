package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    @Column(name = "obligado_nombre", length = 250)
    private String issuerName;

    @Column(name = "obligado_nif", length = 9)
    private String issuerTaxId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "obligado_direccion", columnDefinition = "jsonb")
    private Map<String, String> issuerAddress;

    @Column(name = "xml_sin_firmar", nullable = false, columnDefinition = "text")
    private String unsignedXml;

    @Column(name = "xml_firmado", columnDefinition = "text")
    private String signedXml;

    @Column(name = "xml_hash", nullable = false, length = 64)
    private String xmlHash;

    @Column(name = "certificado_huella", length = 128)
    private String certificateFingerprint;

    @Column(name = "qr_url", columnDefinition = "text")
    private String qrUrl;

    @Column(name = "qr_hash", length = 64)
    private String qrHash;

    @Column(name = "qr_prefijo", length = 64)
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
            String issuerName,
            String issuerTaxId,
            Map<String, String> issuerAddress,
            String unsignedXml,
            String signedXml,
            String certificateFingerprint,
            String xmlHash,
            FiscalPrintSnapshot printSnapshot,
            Instant createdAt) {
        this.recordId = recordId;
        this.fiscalMode = fiscalMode;
        this.environment = environment;
        this.sandbox = sandbox;
        this.systemVersionId = systemVersionId;
        this.issuerName = required(issuerName, "issuerName");
        this.issuerTaxId = required(issuerTaxId, "issuerTaxId");
        this.issuerAddress = requiredAddress(issuerAddress);
        this.unsignedXml = unsignedXml;
        this.signedXml = signedXml;
        this.certificateFingerprint = certificateFingerprint;
        this.xmlHash = xmlHash;
        copyPrintSnapshot(printSnapshot);
        this.createdAt = createdAt;
    }

    /** Compatibility constructor for fixtures created after V203 but before V207. */
    public FiscalRecordArtifact(
            UUID recordId,
            FiscalMode fiscalMode,
            FiscalEndpointEnvironment environment,
            boolean sandbox,
            UUID systemVersionId,
            String issuerName,
            String issuerTaxId,
            String unsignedXml,
            String signedXml,
            String certificateFingerprint,
            String xmlHash,
            FiscalPrintSnapshot printSnapshot,
            Instant createdAt) {
        this.recordId = recordId;
        this.fiscalMode = fiscalMode;
        this.environment = environment;
        this.sandbox = sandbox;
        this.systemVersionId = systemVersionId;
        this.issuerName = required(issuerName, "issuerName");
        this.issuerTaxId = required(issuerTaxId, "issuerTaxId");
        this.unsignedXml = unsignedXml;
        this.signedXml = signedXml;
        this.certificateFingerprint = certificateFingerprint;
        this.xmlHash = xmlHash;
        copyPrintSnapshot(printSnapshot);
        this.createdAt = createdAt;
    }

    /** Compatibility constructor for historical fixtures created before V203. */
    public FiscalRecordArtifact(
            UUID recordId,
            FiscalMode fiscalMode,
            FiscalEndpointEnvironment environment,
            boolean sandbox,
            UUID systemVersionId,
            String unsignedXml,
            String signedXml,
            String certificateFingerprint,
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
        this.certificateFingerprint = certificateFingerprint;
        this.xmlHash = xmlHash;
        copyPrintSnapshot(printSnapshot);
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
                null, xmlHash, printSnapshot, createdAt);
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
        this(recordId, fiscalMode, environment, sandbox, systemVersionId, unsignedXml, signedXml,
                null, xmlHash, printSnapshot, createdAt);
    }

    public UUID getRecordId() { return recordId; }
    public FiscalMode getFiscalMode() { return fiscalMode; }
    public FiscalEndpointEnvironment getEnvironment() { return environment; }
    public boolean isSandbox() { return sandbox; }
    public UUID getSystemVersionId() { return systemVersionId; }
    public String getIssuerName() { return issuerName; }
    public String getIssuerTaxId() { return issuerTaxId; }
    public Map<String, String> getIssuerAddress() {
        return issuerAddress == null ? null : Collections.unmodifiableMap(issuerAddress);
    }
    public String getUnsignedXml() { return unsignedXml; }
    public String getSignedXml() { return signedXml; }
    public String getCertificateFingerprint() { return certificateFingerprint; }
    public String getXmlHash() { return xmlHash; }
    public String getQrUrl() { return qrUrl; }
    public String getQrHash() { return qrHash; }
    public String getQrPrefix() { return qrPrefix; }
    public String getQrLegend() { return qrLegend; }
    public String getTestNotice() { return testNotice; }
    public Instant getCreatedAt() { return createdAt; }

    private void copyPrintSnapshot(FiscalPrintSnapshot printSnapshot) {
        if (printSnapshot == null) {
            return;
        }
        this.qrUrl = printSnapshot.qrUrl();
        this.qrHash = printSnapshot.qrPayloadSha256();
        this.qrPrefix = printSnapshot.prefix();
        this.qrLegend = printSnapshot.legend();
        this.testNotice = printSnapshot.testNotice();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }

    private static Map<String, String> requiredAddress(Map<String, String> value) {
        if (value == null) {
            throw new IllegalArgumentException("issuerAddress es obligatorio");
        }
        var copy = new LinkedHashMap<>(value);
        for (String key : new String[] {
                "linea1", "codigoPostal", "ciudad", "provincia", "pais"}) {
            copy.put(key, required(copy.get(key), "issuerAddress." + key));
        }
        return copy;
    }
}

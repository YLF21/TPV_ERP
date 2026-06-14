package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Immutable
@Table(name = "registro_fiscal")
public class FiscalRecord {

    @Id
    private UUID id;
    @Column(name = "cadena_id", nullable = false)
    private UUID chainId;
    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;
    @Column(name = "instalacion_id", nullable = false)
    private UUID installationId;
    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;
    @Column(name = "documento_id")
    private UUID documentId;
    @Column(name = "secuencia", nullable = false)
    private long sequence;
    @Enumerated(EnumType.STRING)
    @Column(name = "operacion", nullable = false, length = 16)
    private FiscalRecordOperation operation;
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_documento_fiscal", nullable = false, length = 4)
    private FiscalDocumentType documentType;
    @Column(name = "serie_numero", nullable = false, length = 64)
    private String number;
    @Column(name = "fecha_expedicion", nullable = false)
    private LocalDate issueDate;
    @Column(name = "generado_en", nullable = false)
    private Instant generatedAt;
    @Column(name = "zona_horaria", nullable = false, length = 64)
    private String timezone;
    @Column(name = "nif_emisor", nullable = false, length = 9)
    private String issuerTaxId;
    @Column(name = "cuota_total", precision = 19, scale = 2)
    private BigDecimal totalTax;
    @Column(name = "importe_total", precision = 19, scale = 2)
    private BigDecimal totalAmount;
    @Column(name = "huella_anterior", length = 64)
    private String previousHash;
    @Column(name = "huella", nullable = false, length = 64)
    private String hash;
    @Column(name = "hash_snapshot", nullable = false, length = 64)
    private String snapshotHash;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> snapshot;
    @Column(name = "version_formato", nullable = false, length = 16)
    private String formatVersion;
    @Column(name = "version_algoritmo", nullable = false, length = 16)
    private String algorithmVersion;
    @Column(name = "version_aplicacion", nullable = false, length = 32)
    private String applicationVersion;

    protected FiscalRecord() {
    }

    public FiscalRecord(
            UUID chainId,
            UUID companyId,
            UUID installationId,
            UUID storeId,
            UUID documentId,
            long sequence,
            FiscalRecordOperation operation,
            FiscalDocumentType documentType,
            String number,
            LocalDate issueDate,
            Instant generatedAt,
            String timezone,
            String issuerTaxId,
            BigDecimal totalTax,
            BigDecimal totalAmount,
            String previousHash,
            String hash,
            String snapshotHash,
            Map<String, Object> snapshot,
            String formatVersion,
            String algorithmVersion,
            String applicationVersion) {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence debe ser positiva");
        }
        id = UUID.randomUUID();
        this.chainId = Objects.requireNonNull(chainId, "chainId");
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.installationId = Objects.requireNonNull(installationId, "installationId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.documentId = documentId;
        this.sequence = sequence;
        this.operation = Objects.requireNonNull(operation, "operation");
        this.documentType = Objects.requireNonNull(documentType, "documentType");
        this.number = required(number, "number");
        this.issueDate = Objects.requireNonNull(issueDate, "issueDate");
        this.generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
        this.timezone = required(timezone, "timezone");
        this.issuerTaxId = required(issuerTaxId, "issuerTaxId");
        this.totalTax = totalTax;
        this.totalAmount = totalAmount;
        this.previousHash = previousHash;
        this.hash = required(hash, "hash");
        this.snapshotHash = required(snapshotHash, "snapshotHash");
        this.snapshot = ImmutableJson.copy(snapshot);
        this.formatVersion = required(formatVersion, "formatVersion");
        this.algorithmVersion = required(algorithmVersion, "algorithmVersion");
        this.applicationVersion = required(applicationVersion, "applicationVersion");
    }

    public UUID getId() {
        return id;
    }

    public long getSequence() {
        return sequence;
    }

    public String getHash() {
        return hash;
    }

    public String getSnapshotHash() {
        return snapshotHash;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public Map<String, Object> getSnapshot() {
        return ImmutableJson.copy(snapshot);
    }

    UUID chainId() {
        return chainId;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}

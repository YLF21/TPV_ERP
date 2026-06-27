package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "certificado_verifactu")
public class ManagedVerifactuCertificate {

    @Id
    private UUID id;
    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ManagedCertificateStatus status;
    @Column(nullable = false, length = 512)
    private String subject;
    @Column(nullable = false, length = 512)
    private String issuer;
    @Column(name = "serial_number", nullable = false, length = 128)
    private String serialNumber;
    @Column(name = "tax_id", nullable = false, length = 9)
    private String taxId;
    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;
    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;
    @Column(nullable = false, length = 64)
    private String fingerprint;
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "public_chain", nullable = false, columnDefinition = "bytea")
    private byte[] publicChain;
    @Column(name = "secret_path", length = 512)
    private String secretPath;
    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;
    @Column(name = "imported_by", nullable = false)
    private UUID importedBy;
    @Column(name = "replaced_at")
    private Instant replacedAt;
    @Column(name = "replaced_by")
    private UUID replacedBy;
    @Column(name = "deleted_at")
    private Instant deletedAt;
    @Column(name = "deleted_by")
    private UUID deletedBy;
    @Column(name = "last_warning_date")
    private LocalDate lastWarningDate;
    @Version
    private long version;

    protected ManagedVerifactuCertificate() {
    }

    private ManagedVerifactuCertificate(
            UUID id,
            UUID companyId,
            String subject,
            String issuer,
            String serialNumber,
            String taxId,
            Instant validFrom,
            Instant validUntil,
            String fingerprint,
            byte[] publicChain,
            String secretPath,
            Instant importedAt,
            UUID importedBy) {
        this.id = Objects.requireNonNull(id, "id");
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        status = ManagedCertificateStatus.ACTIVO;
        this.subject = required(subject, "subject");
        this.issuer = required(issuer, "issuer");
        this.serialNumber = required(serialNumber, "serialNumber");
        this.taxId = required(taxId, "taxId");
        this.validFrom = Objects.requireNonNull(validFrom, "validFrom");
        this.validUntil = Objects.requireNonNull(validUntil, "validUntil");
        if (!validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("La vigencia del certificado no es valida");
        }
        this.fingerprint = fingerprint(fingerprint);
        this.publicChain = bytes(publicChain, "publicChain");
        this.secretPath = required(secretPath, "secretPath");
        this.importedAt = Objects.requireNonNull(importedAt, "importedAt");
        this.importedBy = Objects.requireNonNull(importedBy, "importedBy");
    }

    // Creates the public record that references an already protected local key.
    public static ManagedVerifactuCertificate active(
            UUID companyId,
            String subject,
            String issuer,
            String serialNumber,
            String taxId,
            Instant validFrom,
            Instant validUntil,
            String fingerprint,
            byte[] publicChain,
            String secretPath,
            Instant importedAt,
            UUID importedBy) {
        return new ManagedVerifactuCertificate(
                UUID.randomUUID(), companyId, subject, issuer, serialNumber, taxId,
                validFrom, validUntil, fingerprint, publicChain,
                secretPath, importedAt, importedBy);
    }

    static ManagedVerifactuCertificate active(
            UUID id,
            UUID companyId,
            String subject,
            String issuer,
            String serialNumber,
            String taxId,
            Instant validFrom,
            Instant validUntil,
            String fingerprint,
            byte[] publicChain,
            String secretPath,
            Instant importedAt,
            UUID importedBy) {
        return new ManagedVerifactuCertificate(
                id, companyId, subject, issuer, serialNumber, taxId,
                validFrom, validUntil, fingerprint, publicChain,
                secretPath, importedAt, importedBy);
    }

    public void markPrevious(Instant changedAt, UUID userId) {
        if (status != ManagedCertificateStatus.ACTIVO) {
            throw new IllegalStateException("Solo un certificado activo puede sustituirse");
        }
        status = ManagedCertificateStatus.ANTERIOR;
        replacedAt = Objects.requireNonNull(changedAt, "changedAt");
        replacedBy = Objects.requireNonNull(userId, "userId");
    }

    public void removeSecret(Instant changedAt) {
        removeSecret(changedAt, null);
    }

    public void removeSecret(Instant changedAt, UUID userId) {
        if (status != ManagedCertificateStatus.ANTERIOR) {
            throw new IllegalStateException("Solo el certificado anterior puede purgarse");
        }
        status = ManagedCertificateStatus.ELIMINADO;
        secretPath = null;
        deletedAt = Objects.requireNonNull(changedAt, "changedAt");
        deletedBy = userId;
    }

    public void deleteActive(Instant changedAt, UUID userId) {
        if (status != ManagedCertificateStatus.ACTIVO) {
            throw new IllegalStateException("Solo el certificado activo puede eliminarse");
        }
        status = ManagedCertificateStatus.ELIMINADO;
        secretPath = null;
        deletedAt = Objects.requireNonNull(changedAt, "changedAt");
        deletedBy = Objects.requireNonNull(userId, "userId");
    }

    public void markWarning(LocalDate warningDate) {
        if (status != ManagedCertificateStatus.ACTIVO) {
            throw new IllegalStateException("Solo el certificado activo genera avisos");
        }
        lastWarningDate = Objects.requireNonNull(warningDate, "warningDate");
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public ManagedCertificateStatus getStatus() {
        return status;
    }

    public String getTaxId() {
        return taxId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getSecretPath() {
        return secretPath;
    }

    public Instant getReplacedAt() {
        return replacedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public LocalDate getLastWarningDate() {
        return lastWarningDate;
    }

    public byte[] getPublicChain() {
        return publicChain.clone();
    }

    public String getSubject() {
        return subject;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    private static String fingerprint(String value) {
        var normalized = required(value, "fingerprint");
        if (!normalized.matches("[0-9A-F]{64}")) {
            throw new IllegalArgumentException("La huella del certificado no es valida");
        }
        return normalized;
    }

    private static byte[] bytes(byte[] value, String field) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.clone();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}

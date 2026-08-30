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

/** Immutable software identity referenced by every frozen fiscal artifact. */
@Entity
@Immutable
@Table(name = "version_sistema_fiscal")
public class FiscalSystemVersion {

    @Id
    private UUID id;
    @Column(name = "empresa_id", nullable = false) private UUID companyId;
    @Column(name = "instalacion_id", nullable = false) private UUID installationId;
    @Column(name = "productor_nif", nullable = false, length = 32) private String producerTaxId;
    @Column(name = "productor_nombre", nullable = false, length = 250) private String producerName;
    @Column(name = "nombre_sistema", nullable = false, length = 250) private String systemName;
    @Column(name = "id_sistema", nullable = false, length = 100) private String systemId;
    @Column(name = "version_sistema", nullable = false, length = 100) private String systemVersion;
    @Column(name = "numero_instalacion", nullable = false, length = 100)
    private String installationNumber;
    @Column(name = "declaracion_hash", length = 64) private String declarationHash;
    @Column(name = "release_id", nullable = false, length = 128) private String releaseId;
    @Column(name = "artifact_hash", length = 64) private String artifactHash;
    @Column(name = "commit_hash", length = 64) private String commitHash;
    @Column(name = "capacidad_producto", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private FiscalProductCapability productCapability;
    @Column(name = "esquema_version", nullable = false, length = 32) private String schemaVersion;
    @Column(name = "manifest_hash", length = 64) private String manifestHash;
    @Column(name = "sandbox", nullable = false) private boolean sandbox;
    @Column(name = "creado_en", nullable = false) private Instant createdAt;

    protected FiscalSystemVersion() {
    }

    public FiscalSystemVersion(UUID companyId, UUID installationId,
            String producerTaxId, String producerName, String systemName, String systemId,
            String systemVersion, String installationNumber, String declarationHash,
            boolean sandbox, Instant createdAt) {
        this(companyId, installationId, producerTaxId, producerName, systemName, systemId,
                systemVersion, installationNumber, declarationHash, sandbox, createdAt,
                "LEGACY-" + UUID.randomUUID(), null, null, FiscalProductCapability.DUAL, "V216", null);
    }

    public FiscalSystemVersion(UUID companyId, UUID installationId,
            String producerTaxId, String producerName, String systemName, String systemId,
            String systemVersion, String installationNumber, String declarationHash,
            boolean sandbox, Instant createdAt, String releaseId, String artifactHash,
            String commitHash, FiscalProductCapability productCapability, String schemaVersion,
            String manifestHash) {
        this.id = UUID.randomUUID();
        this.companyId = required(companyId, "companyId");
        this.installationId = required(installationId, "installationId");
        this.producerTaxId = required(producerTaxId, "producerTaxId");
        this.producerName = required(producerName, "producerName");
        this.systemName = required(systemName, "systemName");
        this.systemId = required(systemId, "systemId");
        this.systemVersion = required(systemVersion, "systemVersion");
        this.installationNumber = required(installationNumber, "installationNumber");
        this.declarationHash = declarationHash(declarationHash);
        this.sandbox = sandbox;
        this.createdAt = required(createdAt, "createdAt");
        this.releaseId = required(releaseId, "releaseId");
        this.artifactHash = optionalHash(artifactHash, "artifactHash");
        this.commitHash = optionalCommit(commitHash);
        this.productCapability = java.util.Objects.requireNonNull(
                productCapability, "productCapability");
        this.schemaVersion = required(schemaVersion, "schemaVersion");
        this.manifestHash = optionalHash(manifestHash, "manifestHash");
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getInstallationId() { return installationId; }
    public String getProducerTaxId() { return producerTaxId; }
    public String getProducerName() { return producerName; }
    public String getSystemName() { return systemName; }
    public String getSystemId() { return systemId; }
    public String getSystemVersion() { return systemVersion; }
    public String getInstallationNumber() { return installationNumber; }
    public String getDeclarationHash() { return declarationHash; }
    public boolean isSandbox() { return sandbox; }
    public Instant getCreatedAt() { return createdAt; }
    public String getReleaseId() { return releaseId; }
    public String getArtifactHash() { return artifactHash; }
    public String getCommitHash() { return commitHash; }
    public FiscalProductCapability getProductCapability() { return productCapability; }
    public String getSchemaVersion() { return schemaVersion; }
    public String getManifestHash() { return manifestHash; }

    public boolean matches(String producerTaxId, String producerName,
            String systemName, String systemId, String systemVersion,
            String installationNumber, String declarationHash, boolean sandbox) {
        return this.producerTaxId.equals(producerTaxId)
                && this.producerName.equals(producerName)
                && this.systemName.equals(systemName)
                && this.systemId.equals(systemId)
                && this.systemVersion.equals(systemVersion)
                && this.installationNumber.equals(installationNumber)
                && java.util.Objects.equals(
                        declarationHash(this.declarationHash),
                        declarationHash(declarationHash))
                && this.sandbox == sandbox;
    }

    /**
     * Compares the persisted release identity with the packaged release and
     * the hash resolved from the final running artifact sidecar. The artifact
     * digest is intentionally not part of the embedded manifest because that
     * would make the JAR self-referential.
     */
    public boolean matchesRelease(FiscalReleaseManifest manifest, String resolvedArtifactHash) {
        return manifest != null
                && manifest.releaseId().equals(releaseId)
                && java.util.Objects.equals(artifactHash, resolvedArtifactHash)
                && java.util.Objects.equals(commitHash, manifest.commitHash())
                && productCapability == manifest.capability()
                && schemaVersion.equals(manifest.schemaVersion())
                && java.util.Objects.equals(manifestHash, manifest.manifestHash());
    }

    private static String declarationHash(String value) {
        var normalized = value == null
                ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        if (!normalized.matches("[0-9A-F]{64}")) {
            throw new IllegalArgumentException(
                    "declarationHash debe ser un SHA-256 de 64 hexadecimales");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }

    private static String optionalHash(String value, String field) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9A-F]{64}")) {
            throw new IllegalArgumentException(field + " debe ser SHA-256");
        }
        return normalized;
    }

    private static String optionalCommit(String value) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{7,64}")) {
            throw new IllegalArgumentException("commitHash debe ser hexadecimal");
        }
        return normalized;
    }

    private static UUID required(UUID value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value;
    }

    private static Instant required(Instant value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value;
    }
}

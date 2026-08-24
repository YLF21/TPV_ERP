package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
    @Column(name = "sandbox", nullable = false) private boolean sandbox;
    @Column(name = "creado_en", nullable = false) private Instant createdAt;

    protected FiscalSystemVersion() {
    }

    public FiscalSystemVersion(UUID companyId, UUID installationId,
            String producerTaxId, String producerName, String systemName, String systemId,
            String systemVersion, String installationNumber, String declarationHash,
            boolean sandbox, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.companyId = required(companyId, "companyId");
        this.installationId = required(installationId, "installationId");
        this.producerTaxId = required(producerTaxId, "producerTaxId");
        this.producerName = required(producerName, "producerName");
        this.systemName = required(systemName, "systemName");
        this.systemId = required(systemId, "systemId");
        this.systemVersion = required(systemVersion, "systemVersion");
        this.installationNumber = required(installationNumber, "installationNumber");
        this.declarationHash = declarationHash == null || declarationHash.isBlank()
                ? null : declarationHash.trim();
        this.sandbox = sandbox;
        this.createdAt = required(createdAt, "createdAt");
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

    public boolean matches(String producerTaxId, String producerName,
            String systemName, String systemId, String systemVersion,
            String installationNumber, boolean sandbox) {
        return this.producerTaxId.equals(producerTaxId)
                && this.producerName.equals(producerName)
                && this.systemName.equals(systemName)
                && this.systemId.equals(systemId)
                && this.systemVersion.equals(systemVersion)
                && this.installationNumber.equals(installationNumber)
                && this.sandbox == sandbox;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
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

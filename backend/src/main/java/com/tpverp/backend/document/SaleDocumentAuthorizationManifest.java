package com.tpverp.backend.document;

import com.tpverp.backend.security.sales.SaleOperationCode;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "manifiesto_autorizacion_documento_venta")
public class SaleDocumentAuthorizationManifest {

    static final int FORMAT_VERSION = 1;
    static final String ALGORITHM = "SHA-256";

    @Id
    @Column(name = "documento_id")
    private UUID documentId;

    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Column(name = "version_formato", nullable = false)
    private int formatVersion = FORMAT_VERSION;

    @Column(nullable = false, length = 16)
    private String algoritmo = ALGORITHM;

    @Column(nullable = false, length = 64)
    private String huella;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "manifiesto_autorizacion_documento_venta_operacion",
            joinColumns = @JoinColumn(name = "documento_id"))
    @MapKeyEnumerated(EnumType.STRING)
    @MapKeyColumn(name = "codigo_operacion", length = 64)
    @Column(name = "version_politica", nullable = false)
    private Map<SaleOperationCode, Long> policyVersions =
            new EnumMap<>(SaleOperationCode.class);

    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;

    @Column(name = "actualizado_en", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected SaleDocumentAuthorizationManifest() {
    }

    public SaleDocumentAuthorizationManifest(
            UUID documentId,
            UUID storeId,
            String fingerprint,
            Map<SaleOperationCode, Long> policyVersions,
            Instant now) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.huella = fingerprint(fingerprint);
        replacePolicyVersions(policyVersions);
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public boolean matches(String fingerprint) {
        return huella.equals(fingerprint(fingerprint));
    }

    public void refreshPolicies(
            String fingerprint,
            Map<SaleOperationCode, Long> versions,
            Instant now) {
        if (!matches(fingerprint)) {
            throw GenericSaleConfirmationBlockedException.mismatch();
        }
        replacePolicyVersions(versions);
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void replaceAfterAuthorization(
            String fingerprint,
            Map<SaleOperationCode, Long> versions,
            Instant now) {
        huella = fingerprint(fingerprint);
        replacePolicyVersions(versions);
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public Map<SaleOperationCode, Long> getPolicyVersions() {
        return Map.copyOf(policyVersions);
    }

    private void replacePolicyVersions(Map<SaleOperationCode, Long> versions) {
        policyVersions.clear();
        if (versions == null) {
            return;
        }
        versions.forEach((code, version) -> {
            Objects.requireNonNull(code, "operationCode");
            if (version == null || version < 0) {
                throw new IllegalArgumentException(
                        "policyVersion must be non-negative");
            }
            policyVersions.put(code, version);
        });
    }

    private static String fingerprint(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "sale_document_authorization_fingerprint_invalid");
        }
        return value;
    }
}

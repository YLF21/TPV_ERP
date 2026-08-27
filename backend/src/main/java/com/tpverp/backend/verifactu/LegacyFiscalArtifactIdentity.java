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

/** Verified, immutable identity recovered from pre-V203 fiscal evidence. */
@Entity
@Immutable
@Table(name = "identidad_legacy_artefacto_fiscal")
class LegacyFiscalArtifactIdentity {

    @Id
    @Column(name = "registro_id")
    private UUID recordId;

    @Column(name = "obligado_nombre", nullable = false, length = 250)
    private String issuerName;

    @Column(name = "obligado_nif", nullable = false, length = 9)
    private String issuerTaxId;

    @Enumerated(EnumType.STRING)
    @Column(name = "fuente", nullable = false, length = 32)
    private LegacyFiscalIdentitySource source;

    @Column(name = "resuelto_en", nullable = false)
    private Instant resolvedAt;

    protected LegacyFiscalArtifactIdentity() {
    }

    LegacyFiscalArtifactIdentity(
            UUID recordId,
            String issuerName,
            String issuerTaxId,
            LegacyFiscalIdentitySource source,
            Instant resolvedAt) {
        this.recordId = Objects.requireNonNull(recordId, "recordId");
        this.issuerName = required(issuerName, "issuerName");
        this.issuerTaxId = required(issuerTaxId, "issuerTaxId");
        this.source = Objects.requireNonNull(source, "source");
        this.resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
    }

    UUID getRecordId() { return recordId; }
    String getIssuerName() { return issuerName; }
    String getIssuerTaxId() { return issuerTaxId; }
    LegacyFiscalIdentitySource getSource() { return source; }
    Instant getResolvedAt() { return resolvedAt; }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}

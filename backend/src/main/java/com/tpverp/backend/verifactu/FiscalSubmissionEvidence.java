package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * Immutable request evidence for one AEAT batch. The response is an
 * append-only child because it is not known when the request is committed.
 * Keeping the two payloads in separate rows also prevents a response from
 * replacing or mutating the request that was put on the wire.
 */
@Entity
@Immutable
@Table(name = "evidencia_envio_fiscal")
public class FiscalSubmissionEvidence {

    /** Leaves room for a 1000-record AEAT batch plus SOAP envelope overhead. */
    public static final int MAX_REQUEST_BYTES = 64 * 1024 * 1024;

    @Id
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;

    @Column(name = "instalacion_id", nullable = false)
    private UUID installationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entorno", nullable = false, length = 16)
    private FiscalEndpointEnvironment environment;

    @Column(name = "batch_owner", nullable = false)
    private UUID batchOwner;

    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;

    @Column(name = "request_preparado_en", nullable = false)
    private Instant requestPreparedAt;

    @Column(name = "request_xml", nullable = false, columnDefinition = "text")
    private String requestXml;

    @Column(name = "request_sha256", nullable = false, length = 64)
    private String requestSha256;

    protected FiscalSubmissionEvidence() {
    }

    public FiscalSubmissionEvidence(
            UUID batchId,
            UUID companyId,
            UUID installationId,
            FiscalEndpointEnvironment environment,
            UUID batchOwner,
            Instant createdAt,
            Instant requestPreparedAt,
            String requestXml,
            String requestSha256) {
        this.id = Objects.requireNonNull(batchId, "batchId");
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.installationId = Objects.requireNonNull(installationId, "installationId");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.batchOwner = Objects.requireNonNull(batchOwner, "batchOwner");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.requestPreparedAt = Objects.requireNonNull(requestPreparedAt, "requestPreparedAt");
        this.requestXml = requiredPayload(requestXml, "requestXml", MAX_REQUEST_BYTES);
        this.requestSha256 = requiredHash(requestSha256, "requestSha256");
        if (!this.requestSha256.equals(sha256(this.requestXml))) {
            throw new IllegalArgumentException("requestSha256 no coincide con requestXml");
        }
    }

    public UUID getId() { return id; }
    /** The immutable batch identity is also the evidence identifier. */
    public UUID getBatchId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getInstallationId() { return installationId; }
    public FiscalEndpointEnvironment getEnvironment() { return environment; }
    public UUID getBatchOwner() { return batchOwner; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRequestPreparedAt() { return requestPreparedAt; }
    public String getRequestXml() { return requestXml; }
    public String getRequestSha256() { return requestSha256; }

    static String sha256(String payload) {
        try {
            return java.util.HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    static String requiredPayload(String value, String field, int maxBytes) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(field + " supera el limite permitido");
        }
        return value;
    }

    static String optionalPayload(String value, String field, int maxBytes) {
        if (value == null) return null;
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(field + " supera el limite permitido");
        }
        return value;
    }

    private static String requiredHash(String value, String field) {
        if (value == null || !value.matches("[0-9A-Fa-f]{64}")) {
            throw new IllegalArgumentException(field + " debe ser SHA-256 hexadecimal");
        }
        return value.toUpperCase(java.util.Locale.ROOT);
    }
}

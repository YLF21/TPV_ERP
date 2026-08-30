package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Immutable, exact response payload associated with a request evidence row. */
@Entity
@Immutable
@Table(name = "respuesta_evidencia_envio_fiscal")
public class FiscalSubmissionResponseEvidence {

    /** Aligned with HttpVerifactuTransport's bounded response reader. */
    public static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

    @Id
    private UUID id;

    @Column(name = "evidencia_id", nullable = false, unique = true)
    private UUID evidenceId;

    @Column(name = "recibido_en", nullable = false)
    private Instant receivedAt;

    @Column(name = "response_payload", nullable = false, columnDefinition = "text")
    private String responsePayload;

    @Column(name = "response_sha256", nullable = false, length = 64)
    private String responseSha256;

    protected FiscalSubmissionResponseEvidence() {
    }

    public FiscalSubmissionResponseEvidence(
            UUID evidenceId,
            Instant receivedAt,
            String responsePayload,
            String responseSha256) {
        this.id = UUID.randomUUID();
        this.evidenceId = Objects.requireNonNull(evidenceId, "evidenceId");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        if (responsePayload == null) {
            throw new IllegalArgumentException("responsePayload es obligatorio");
        }
        this.responsePayload = FiscalSubmissionEvidence.optionalPayload(
                responsePayload, "responsePayload", MAX_RESPONSE_BYTES);
        if (responseSha256 == null || !responseSha256.matches("[0-9A-Fa-f]{64}")) {
            throw new IllegalArgumentException("responseSha256 debe ser SHA-256 hexadecimal");
        }
        this.responseSha256 = responseSha256.toUpperCase(java.util.Locale.ROOT);
        if (!this.responseSha256.equals(FiscalSubmissionEvidence.sha256(this.responsePayload))) {
            throw new IllegalArgumentException("responseSha256 no coincide con responsePayload");
        }
    }

    public UUID getId() { return id; }
    public UUID getEvidenceId() { return evidenceId; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getResponsePayload() { return responsePayload; }
    public String getResponseSha256() { return responseSha256; }
}

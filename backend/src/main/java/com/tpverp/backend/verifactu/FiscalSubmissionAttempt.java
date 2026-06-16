package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "intento_envio_fiscal")
public class FiscalSubmissionAttempt {

    @Id
    private UUID id;

    @Column(name = "registro_id", nullable = false)
    private UUID recordId;

    @Column(name = "intentado_en", nullable = false)
    private Instant attemptedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 24)
    private FiscalSubmissionStatus status;

    @Column(name = "error_codigo", length = 64)
    private String errorCode;

    @Column(name = "error")
    private String error;

    @Column(name = "xml_enviado")
    private String requestXml;

    @Column(name = "respuesta")
    private String responsePayload;

    @Version
    private long version;

    protected FiscalSubmissionAttempt() {
    }

    public FiscalSubmissionAttempt(
            UUID recordId,
            Instant attemptedAt,
            FiscalSubmissionStatus status,
            String errorCode,
            String error,
            String requestXml,
            String responsePayload) {
        this.id = UUID.randomUUID();
        this.recordId = Objects.requireNonNull(recordId, "recordId");
        this.attemptedAt = Objects.requireNonNull(attemptedAt, "attemptedAt");
        this.status = Objects.requireNonNull(status, "status");
        this.errorCode = optional(errorCode);
        this.error = optional(error);
        this.requestXml = optional(requestXml);
        this.responsePayload = optional(responsePayload);
    }

    public UUID getId() {
        return id;
    }

    public UUID getRecordId() {
        return recordId;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    public FiscalSubmissionStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getError() {
        return error;
    }

    public String getRequestXml() {
        return requestXml;
    }

    public String getResponsePayload() {
        return responsePayload;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

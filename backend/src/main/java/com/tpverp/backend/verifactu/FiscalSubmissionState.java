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
@Table(name = "estado_envio_fiscal")
public class FiscalSubmissionState {

    @Id
    @Column(name = "registro_id")
    private UUID recordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 24)
    private FiscalSubmissionStatus status;

    @Column(name = "ultimo_error_codigo", length = 64)
    private String lastErrorCode;

    @Column(name = "ultimo_error")
    private String lastError;

    @Column(name = "actualizado_en", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected FiscalSubmissionState() {
    }

    FiscalSubmissionState(
            UUID recordId,
            FiscalSubmissionStatus status,
            Instant updatedAt) {
        this.recordId = Objects.requireNonNull(recordId, "recordId");
        this.status = Objects.requireNonNull(status, "status");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public UUID getRecordId() {
        return recordId;
    }

    public FiscalSubmissionStatus getStatus() {
        return status;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

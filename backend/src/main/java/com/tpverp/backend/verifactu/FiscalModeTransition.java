package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "transicion_modo_fiscal")
public class FiscalModeTransition {
    @Id private UUID id;
    @Column(name = "empresa_id", nullable = false) private UUID companyId;
    @Column(name = "instalacion_id", nullable = false) private UUID installationId;
    @Enumerated(EnumType.STRING) @Column(name = "modo_anterior", nullable = false, length = 16)
    private FiscalMode previousMode;
    @Enumerated(EnumType.STRING) @Column(name = "modo_nuevo", nullable = false, length = 16)
    private FiscalMode newMode;
    @Column(name = "solicitada_en", nullable = false) private Instant requestedAt;
    @Column(name = "efectiva_en", nullable = false) private Instant effectiveAt;
    @Column(nullable = false, length = 32) private String causa;
    @Column(nullable = false, columnDefinition = "text") private String motivo;
    @Column(name = "expected_version", nullable = false) private long expectedVersion;
    @Enumerated(EnumType.STRING) @Column(name = "estado", nullable = false, length = 16)
    private FiscalModeTransitionStatus status;
    @Column(name = "fecha_fin_verifactu") private LocalDate verifactuEndDate;
    @Column(name = "ack_aeat", length = 128) private String aeatAckReference;
    @Column(name = "transicion_origen_id") private UUID sourceTransitionId;
    @Column(name = "ultimo_error_codigo", length = 64) private String lastErrorCode;
    @Column(name = "ultimo_error", columnDefinition = "text") private String lastError;

    protected FiscalModeTransition() {}

    public FiscalModeTransition(UUID companyId, UUID installationId, FiscalMode previousMode,
            FiscalMode newMode, Instant requestedAt, String cause, String reason,
            long expectedVersion) {
        this(companyId, installationId, previousMode, newMode, requestedAt, requestedAt,
                cause, reason, expectedVersion);
    }

    public FiscalModeTransition(UUID companyId, UUID installationId, FiscalMode previousMode,
            FiscalMode newMode, Instant requestedAt, Instant effectiveAt,
            String cause, String reason, long expectedVersion) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.installationId = installationId;
        this.previousMode = previousMode;
        this.newMode = newMode;
        this.requestedAt = requestedAt;
        this.effectiveAt = effectiveAt;
        this.causa = cause;
        this.motivo = reason;
        this.expectedVersion = expectedVersion;
        this.status = FiscalModeTransitionStatus.APLICADA;
    }

    public FiscalModeTransition(UUID companyId, UUID installationId, FiscalMode previousMode,
            FiscalMode newMode, Instant requestedAt, Instant effectiveAt,
            String cause, String reason, long expectedVersion,
            LocalDate verifactuEndDate, String aeatAckReference) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.installationId = installationId;
        this.previousMode = previousMode;
        this.newMode = newMode;
        this.requestedAt = requestedAt;
        this.effectiveAt = effectiveAt;
        this.causa = cause;
        this.motivo = reason;
        this.expectedVersion = expectedVersion;
        this.status = FiscalModeTransitionStatus.PROGRAMADA;
        this.verifactuEndDate = verifactuEndDate;
        this.aeatAckReference = aeatAckReference;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getInstallationId() { return installationId; }
    public FiscalMode getPreviousMode() { return previousMode; }
    public FiscalMode getNewMode() { return newMode; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public FiscalModeTransitionStatus getStatus() { return status; }
    public LocalDate getVerifactuEndDate() { return verifactuEndDate; }
    public String getAeatAckReference() { return aeatAckReference; }
    public UUID getSourceTransitionId() { return sourceTransitionId; }
    public String getLastErrorCode() { return lastErrorCode; }
    public String getLastError() { return lastError; }
    public long getExpectedVersion() { return expectedVersion; }
    public String getCause() { return causa; }
    public String getReason() { return motivo; }

    static FiscalModeTransition failed(
            FiscalModeTransition scheduled,
            Instant failedAt,
            String errorCode,
            String error) {
        var failureEffectiveAt = failedAt.equals(scheduled.getEffectiveAt())
                ? failedAt.plusMillis(1) : failedAt;
        var failure = new FiscalModeTransition(
                scheduled.getCompanyId(), scheduled.getInstallationId(),
                scheduled.getPreviousMode(), scheduled.getNewMode(), failedAt,
                failureEffectiveAt, "SCHEDULED_WORKER",
                "Fallo al aplicar la transicion programada",
                scheduled.getExpectedVersion(), scheduled.getVerifactuEndDate(),
                scheduled.getAeatAckReference());
        failure.status = FiscalModeTransitionStatus.FALLIDA;
        failure.sourceTransitionId = scheduled.getId();
        failure.lastErrorCode = required(errorCode, "errorCode");
        failure.lastError = required(error, "error");
        return failure;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}

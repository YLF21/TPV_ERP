package com.tpverp.backend.backup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ejecucion_backup")
public class BackupExecution {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "configuracion_id", nullable = false)
    private BackupSettings configuracion;

    @Column(name = "iniciada_en", nullable = false)
    private Instant iniciadaEn;

    @Column(name = "finalizada_en")
    private Instant finalizadaEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 32)
    private BackupResult resultado = BackupResult.EN_CURSO;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "error_reason", columnDefinition = "text")
    private String motivoError;

    /** Opaque worker fencing token. It is never returned by an API. */
    @Column(name = "worker_token")
    private UUID workerToken;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Version
    private long version;

    protected BackupExecution() {
    }

    public BackupExecution(BackupSettings configuracion, Instant iniciadaEn) {
        this.id = UUID.randomUUID();
        this.configuracion = Objects.requireNonNull(configuracion, "configuracion");
        this.iniciadaEn = Objects.requireNonNull(iniciadaEn, "iniciadaEn");
    }

    public BackupExecution(BackupSettings configuracion, Instant iniciadaEn,
            UUID workerToken, Instant heartbeatAt, Instant leaseUntil) {
        this(configuracion, iniciadaEn);
        this.workerToken = Objects.requireNonNull(workerToken, "workerToken");
        this.heartbeatAt = Objects.requireNonNull(heartbeatAt, "heartbeatAt");
        this.leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
    }

    public void completar(BackupResult resultado, Instant finalizadaEn, Map<String, Object> metadata, String motivoError) {
        if (resultado == BackupResult.EN_CURSO) {
            throw new IllegalArgumentException("Una ejecucion completada no puede quedar EN_CURSO");
        }
        if (resultado == BackupResult.FALLO && (motivoError == null || motivoError.isBlank())) {
            throw new IllegalArgumentException("Un backup fallido requiere motivo");
        }
        this.resultado = Objects.requireNonNull(resultado, "resultado");
        this.finalizadaEn = Objects.requireNonNull(finalizadaEn, "finalizadaEn");
        this.metadata = metadata == null ? null : new LinkedHashMap<>(metadata);
        this.motivoError = resultado == BackupResult.FALLO ? motivoError.trim() : null;
        this.leaseUntil = finalizadaEn;
    }

    public UUID getId() {
        return id;
    }

    public Instant getIniciadaEn() {
        return iniciadaEn;
    }

    public Instant getFinalizadaEn() {
        return finalizadaEn;
    }

    public BackupResult getResultado() {
        return resultado;
    }

    public Map<String, Object> getMetadata() {
        return metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public String getMotivoError() {
        return motivoError;
    }

    UUID getWorkerToken() { return workerToken; }
    Instant getHeartbeatAt() { return heartbeatAt; }
    Instant getLeaseUntil() { return leaseUntil; }
}

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
public class EjecucionBackup {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "configuracion_id", nullable = false)
    private ConfiguracionBackup configuracion;

    @Column(name = "iniciada_en", nullable = false)
    private Instant iniciadaEn;

    @Column(name = "finalizada_en")
    private Instant finalizadaEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 32)
    private ResultadoBackup resultado = ResultadoBackup.EN_CURSO;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "error_reason", columnDefinition = "text")
    private String motivoError;

    @Version
    private long version;

    protected EjecucionBackup() {
    }

    public EjecucionBackup(ConfiguracionBackup configuracion, Instant iniciadaEn) {
        this.id = UUID.randomUUID();
        this.configuracion = Objects.requireNonNull(configuracion, "configuracion");
        this.iniciadaEn = Objects.requireNonNull(iniciadaEn, "iniciadaEn");
    }

    public void completar(ResultadoBackup resultado, Instant finalizadaEn, Map<String, Object> metadata, String motivoError) {
        if (resultado == ResultadoBackup.EN_CURSO) {
            throw new IllegalArgumentException("Una ejecucion completada no puede quedar EN_CURSO");
        }
        if (resultado == ResultadoBackup.FALLO && (motivoError == null || motivoError.isBlank())) {
            throw new IllegalArgumentException("Un backup fallido requiere motivo");
        }
        this.resultado = Objects.requireNonNull(resultado, "resultado");
        this.finalizadaEn = Objects.requireNonNull(finalizadaEn, "finalizadaEn");
        this.metadata = metadata == null ? null : new LinkedHashMap<>(metadata);
        this.motivoError = resultado == ResultadoBackup.FALLO ? motivoError.trim() : null;
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

    public ResultadoBackup getResultado() {
        return resultado;
    }

    public Map<String, Object> getMetadata() {
        return metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public String getMotivoError() {
        return motivoError;
    }
}

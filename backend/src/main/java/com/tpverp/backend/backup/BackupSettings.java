package com.tpverp.backend.backup;

import com.tpverp.backend.installation.Installation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "configuracion_backup")
public class BackupSettings {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instalacion_id", nullable = false, unique = true)
    private Installation instalacion;

    @Column(nullable = false)
    private LocalTime hora = LocalTime.NOON;

    @Column(name = "daily_retention", nullable = false)
    private int retencionDiaria = 30;

    @Column(name = "monthly_retention", nullable = false)
    private int retencionMensual = 72;

    @Column(nullable = false)
    private boolean activa = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> destino;

    @Version
    private long version;

    protected BackupSettings() {
    }

    public BackupSettings(
            Installation instalacion,
            LocalTime hora,
            int retencionDiaria,
            int retencionMensual,
            Map<String, Object> destino) {
        if (retencionDiaria < 30 || retencionMensual < 72) {
            throw new IllegalArgumentException("La retencion minima es 30 diaria y 72 mensual");
        }
        this.id = UUID.randomUUID();
        this.instalacion = Objects.requireNonNull(instalacion, "instalacion");
        this.hora = Objects.requireNonNullElse(hora, LocalTime.NOON);
        this.retencionDiaria = retencionDiaria;
        this.retencionMensual = retencionMensual;
        this.destino = destino == null ? null : new LinkedHashMap<>(destino);
    }

    public UUID getId() {
        return id;
    }

    public LocalTime getHora() {
        return hora;
    }

    public int getRetencionDiaria() {
        return retencionDiaria;
    }

    public int getRetencionMensual() {
        return retencionMensual;
    }

    public boolean isActiva() {
        return activa;
    }

    public Map<String, Object> getDestino() {
        return destino == null ? Map.of() : Map.copyOf(destino);
    }

    public void configurar(
            LocalTime nuevaHora,
            int nuevaRetencionDiaria,
            int nuevaRetencionMensual,
            Map<String, Object> nuevoDestino,
            boolean activa) {
        if (nuevaRetencionDiaria < retencionDiaria || nuevaRetencionMensual < retencionMensual) {
            throw new IllegalArgumentException("Las retenciones solo se pueden aumentar");
        }
        this.hora = Objects.requireNonNull(nuevaHora, "nuevaHora");
        this.retencionDiaria = nuevaRetencionDiaria;
        this.retencionMensual = nuevaRetencionMensual;
        this.destino = new LinkedHashMap<>(Objects.requireNonNull(nuevoDestino, "nuevoDestino"));
        this.activa = activa;
    }
}

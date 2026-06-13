package com.tpverp.backend.audit;

import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.security.domain.Usuario;
import com.tpverp.backend.terminal.Terminal;
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
@Table(name = "auditoria")
public class Auditoria {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tienda_id")
    private Tienda tienda;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "terminal_id")
    private Terminal terminal;

    @Column(name = "event", nullable = false, length = 128)
    private String evento;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 32)
    private ResultadoAuditoria resultado;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> datos;

    @Column(name = "creada_en", nullable = false)
    private Instant creadaEn;

    @Version
    private long version;

    protected Auditoria() {
    }

    public Auditoria(
            Tienda tienda,
            Usuario usuario,
            Terminal terminal,
            String evento,
            ResultadoAuditoria resultado,
            Map<String, Object> datos,
            Instant creadaEn) {
        this.id = UUID.randomUUID();
        this.tienda = tienda;
        this.usuario = usuario;
        this.terminal = terminal;
        this.evento = required(evento);
        this.resultado = Objects.requireNonNull(resultado, "resultado");
        this.datos = datos == null ? null : new LinkedHashMap<>(datos);
        this.creadaEn = Objects.requireNonNull(creadaEn, "creadaEn");
    }

    public UUID getId() {
        return id;
    }

    public String getEvento() {
        return evento;
    }

    public ResultadoAuditoria getResultado() {
        return resultado;
    }

    public Map<String, Object> getDatos() {
        return datos == null ? Map.of() : Map.copyOf(datos);
    }

    public Instant getCreadaEn() {
        return creadaEn;
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("evento es obligatorio");
        }
        return value.trim();
    }
}

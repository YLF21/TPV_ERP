package com.tpverp.backend.terminal;

import com.tpverp.backend.organization.Store;
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
import java.net.InetAddress;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "terminal")
public class Terminal {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tienda_id", nullable = false)
    private Store tienda;

    @Column(nullable = false, length = 128)
    private String nombre;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TerminalType tipo;

    @Column(nullable = false)
    private boolean activa = true;

    @Column(nullable = false)
    private boolean aprobada = true;

    @Column(name = "credential_hash", nullable = false)
    private String credentialHash;

    @Column(name = "last_ip", columnDefinition = "inet")
    private InetAddress lastIp;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Version
    private long version;

    protected Terminal() {
    }

    public Terminal(Store tienda, String nombre, TerminalType tipo, String credentialHash) {
        this.id = UUID.randomUUID();
        this.tienda = Objects.requireNonNull(tienda, "tienda");
        this.nombre = required(nombre, "nombre");
        this.tipo = Objects.requireNonNull(tipo, "tipo");
        this.credentialHash = required(credentialHash, "credentialHash");
    }

    public static Terminal request(
            Store tienda,
            String nombre,
            TerminalType tipo,
            String credentialHash) {
        if (tipo == TerminalType.SERVIDOR) {
            throw new IllegalArgumentException("No se puede request otra terminal servidor");
        }
        Terminal terminal = new Terminal(tienda, nombre, tipo, credentialHash);
        terminal.aprobada = false;
        terminal.activa = false;
        return terminal;
    }

    public String getNombre() {
        return nombre;
    }

    public UUID getId() {
        return id;
    }

    public Store getTienda() {
        return tienda;
    }

    public boolean isActiva() {
        return activa;
    }

    public boolean isAprobada() {
        return aprobada;
    }

    public TerminalType getTipo() {
        return tipo;
    }

    public String getCredentialHash() {
        return credentialHash;
    }

    public void approve() {
        aprobada = true;
        activa = true;
    }

    public void deactivate() {
        if (tipo == TerminalType.SERVIDOR) {
            throw new IllegalStateException("La terminal servidor no se puede deactivate");
        }
        activa = false;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}

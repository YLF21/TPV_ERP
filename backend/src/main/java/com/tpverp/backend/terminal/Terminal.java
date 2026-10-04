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

    @Column(name = "workstation_code", length = 12)
    private String workstationCode;

    @Column(name = "current_binding_id")
    private UUID currentBindingId;

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
        this.workstationCode = tipo == TerminalType.SERVIDOR ? "001" : null;
        this.credentialHash = required(credentialHash, "credentialHash");
    }

    public static Terminal request(
            Store tienda,
            String nombre,
            TerminalType tipo,
            String credentialHash) {
        if (tipo == TerminalType.SERVIDOR) {
            throw new IllegalArgumentException("message.terminal.server_request_not_allowed");
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

    public String getWorkstationCode() { return workstationCode; }
    public String getDisplayCode() { return workstationCode == null ? nombre : workstationCode; }
    public UUID getCurrentBindingId() { return currentBindingId; }

    void assignWorkstationCode(String code) {
        if (workstationCode != null && !workstationCode.equals(code)) {
            throw new IllegalStateException("WORKSTATION_ALREADY_ASSIGNED");
        }
        workstationCode = required(code, "code");
    }

    void bind(UUID bindingId) { currentBindingId = bindingId; }

    void endBinding(String unusableCredentialHash) {
        deactivate();
        currentBindingId = null;
        rotateCredential(unusableCredentialHash);
    }

    public void approve() {
        aprobada = true;
        activa = true;
    }

    public void rotateCredential(String credentialHash) {
        this.credentialHash = required(credentialHash, "credentialHash");
    }

    public void deactivate() {
        if (tipo == TerminalType.SERVIDOR) {
            throw new IllegalStateException("message.terminal.server_cannot_deactivate");
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

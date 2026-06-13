package com.tpverp.backend.security.domain;

import com.tpverp.backend.terminal.Terminal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "sesion")
public class Sesion {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "terminal_id")
    private Terminal terminal;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "creada_en", nullable = false)
    private Instant creadaEn;

    @Column(name = "revocada_en")
    private Instant revocadaEn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revocada_por_usuario_id")
    private Usuario revocadaPor;

    @Column(name = "revoke_reason", columnDefinition = "text")
    private String motivoRevocacion;

    @Version
    private long version;

    protected Sesion() {
    }

    public Sesion(Usuario usuario, Terminal terminal, String tokenHash, Instant creadaEn) {
        this.id = UUID.randomUUID();
        this.usuario = Objects.requireNonNull(usuario, "usuario");
        this.terminal = terminal;
        this.tokenHash = required(tokenHash, "tokenHash");
        this.creadaEn = Objects.requireNonNull(creadaEn, "creadaEn");
    }

    public void revocar(Usuario revocadaPor, String motivo, Instant cuando) {
        if (revocadaEn != null) {
            throw new IllegalStateException("La sesion ya esta revocada");
        }
        this.revocadaPor = revocadaPor;
        this.motivoRevocacion = required(motivo, "motivo");
        this.revocadaEn = Objects.requireNonNull(cuando, "cuando");
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public boolean isActiva() {
        return revocadaEn == null;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}

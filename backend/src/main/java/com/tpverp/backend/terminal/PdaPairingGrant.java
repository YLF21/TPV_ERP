package com.tpverp.backend.terminal;

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
@Table(name = "pda_vinculacion_temporal")
public class PdaPairingGrant {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "terminal_id", nullable = false)
    private Terminal terminal;

    @Column(name = "codigo_hash", nullable = false, unique = true, length = 64)
    private String codeHash;

    @Column(name = "emitido_en", nullable = false)
    private Instant issuedAt;

    @Column(name = "expira_en", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumido_en")
    private Instant consumedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected PdaPairingGrant() {
    }

    public PdaPairingGrant(Terminal terminal, String codeHash, Instant issuedAt, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.codeHash = required(codeHash, "codeHash");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public Terminal consume(Instant now) {
        Objects.requireNonNull(now, "now");
        if (consumedAt != null) throw new IllegalStateException("message.terminal.pda_pairing_used");
        if (!expiresAt.isAfter(now)) throw new IllegalStateException("message.terminal.pda_pairing_expired");
        consumedAt = now;
        return terminal;
    }

    public void cancel(Instant now) {
        if (consumedAt == null) consumedAt = Objects.requireNonNull(now, "now");
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field);
        return value.trim();
    }
}

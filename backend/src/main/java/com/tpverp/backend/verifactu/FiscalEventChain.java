package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "cadena_eventos_fiscal")
public class FiscalEventChain {
    @Id
    private UUID id;
    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;
    @Column(name = "instalacion_id", nullable = false)
    private UUID installationId;
    @Column(name = "ultima_secuencia", nullable = false)
    private long lastSequence;
    @Column(name = "ultima_huella", length = 64)
    private String lastHash;
    @Column(name = "actualizada_en", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected FiscalEventChain() {}

    public FiscalEventChain(UUID companyId, UUID installationId, Instant createdAt) {
        id = UUID.randomUUID();
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.installationId = Objects.requireNonNull(installationId, "installationId");
        updatedAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public long nextSequence() { return lastSequence + 1; }
    public String previousHash() { return lastHash; }

    public void advance(long sequence, String hash, Instant at) {
        if (sequence != nextSequence()) {
            throw new IllegalArgumentException("message.fiscal_event_chain.record_does_not_continue_chain");
        }
        lastSequence = sequence;
        lastHash = Objects.requireNonNull(hash, "hash");
        updatedAt = Objects.requireNonNull(at, "at");
    }
}

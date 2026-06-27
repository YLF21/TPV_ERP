package com.tpverp.backend.verifactu;

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
@Table(name = "cadena_fiscal")
public class FiscalChain {

    @Id
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;

    @Column(name = "instalacion_id", nullable = false)
    private UUID installationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ultimo_registro_id")
    private FiscalRecord lastRecord;

    @Column(name = "ultima_huella", length = 64)
    private String lastHash;

    @Column(name = "ultima_secuencia", nullable = false)
    private long lastSequence;

    @Column(name = "actualizada_en", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected FiscalChain() {
    }

    public FiscalChain(UUID companyId, UUID installationId, Instant createdAt) {
        id = UUID.randomUUID();
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.installationId = Objects.requireNonNull(installationId, "installationId");
        updatedAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public long nextSequence() {
        return lastSequence + 1;
    }

    public String previousHash() {
        return lastHash;
    }

    // Advances the chain head only with the next record in this chain.
    public void advance(FiscalRecord record, Instant updatedAt) {
        var next = Objects.requireNonNull(record, "record");
        if (!id.equals(next.chainId()) || next.getSequence() != nextSequence()) {
            throw new IllegalArgumentException("message.fiscal_chain.record_does_not_continue_chain");
        }
        lastRecord = next;
        lastSequence = next.getSequence();
        lastHash = next.getHash();
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    UUID getId() {
        return id;
    }

    FiscalRecord getLastRecord() {
        return lastRecord;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}

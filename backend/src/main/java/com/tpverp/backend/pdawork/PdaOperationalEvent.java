package com.tpverp.backend.pdawork;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "pda_evento_operativo", uniqueConstraints =
        @UniqueConstraint(name = "ux_pda_evento_operativo_cliente", columnNames = {"tienda_id", "terminal_id", "client_event_id"}))
public class PdaOperationalEvent {
    @Id private UUID id;
    @Column(name = "tienda_id", nullable = false) private UUID storeId;
    @Column(name = "terminal_id", nullable = false) private UUID terminalId;
    @Column(name = "client_event_id", nullable = false) private UUID clientEventId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private PdaOperationalEventType tipo;
    @Column(nullable = false, length = 64) private String codigo;
    @Column(name = "trabajo_id") private UUID workId;
    @Column(name = "duracion_ms") private Long durationMs;
    @Column(name = "ocurrido_en", nullable = false) private Instant occurredAt;
    @Column(name = "recibido_en", nullable = false) private Instant receivedAt;
    @Column(name = "trace_id", length = 64) private String traceId;
    @Version private long version;

    protected PdaOperationalEvent() {}

    PdaOperationalEvent(UUID storeId, UUID terminalId, UUID clientEventId, PdaOperationalEventType type,
            String code, UUID workId, Long durationMs, Instant occurredAt, Instant receivedAt, String traceId) {
        this.id = UUID.randomUUID();
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.clientEventId = Objects.requireNonNull(clientEventId, "clientEventId");
        this.tipo = Objects.requireNonNull(type, "type");
        this.codigo = Objects.requireNonNull(code, "code");
        this.workId = workId;
        this.durationMs = durationMs;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        this.traceId = traceId;
    }

    public UUID getId() { return id; }
    public UUID getStoreId() { return storeId; }
    public UUID getTerminalId() { return terminalId; }
    public UUID getClientEventId() { return clientEventId; }
    public PdaOperationalEventType getTipo() { return tipo; }
    public String getCodigo() { return codigo; }
    public UUID getWorkId() { return workId; }
    public Long getDurationMs() { return durationMs; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getTraceId() { return traceId; }
}

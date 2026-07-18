package com.tpverp.backend.control;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "control_alerta")
public class ControlAlert {

    @Id private UUID id;
    @Column(name = "tienda_id", nullable = false) private UUID storeId;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evento_id", nullable = false, unique = true)
    private ControlEvent event;
    @Enumerated(EnumType.STRING) @Column(name = "estado", nullable = false, length = 16)
    private ControlAlertStatus status;
    @Column(name = "creada_en", nullable = false) private Instant createdAt;
    @Column(name = "actualizada_en", nullable = false) private Instant updatedAt;
    @Version private long version;

    protected ControlAlert() {
    }

    public ControlAlert(ControlEvent event) {
        this.id = UUID.randomUUID();
        this.event = Objects.requireNonNull(event, "event");
        this.storeId = event.getStoreId();
        this.status = ControlAlertStatus.NEW;
        this.createdAt = event.getOccurredAt();
        this.updatedAt = event.getOccurredAt();
    }

    public ControlAlertStatus transition(ControlAlertStatus next, Instant now) {
        Objects.requireNonNull(next, "next");
        if (status.isTerminal()) {
            throw new IllegalStateException("Una alerta cerrada o descartada no puede modificarse");
        }
        if (next == ControlAlertStatus.NEW || next == status) {
            throw new IllegalStateException("La transicion de alerta no es valida");
        }
        if (next == ControlAlertStatus.REVIEWED && status != ControlAlertStatus.NEW) {
            throw new IllegalStateException("Solo una alerta nueva puede marcarse como revisada");
        }
        var previous = status;
        status = next;
        updatedAt = Objects.requireNonNull(now, "now");
        return previous;
    }

    public UUID getId() { return id; }
    public UUID getStoreId() { return storeId; }
    public ControlEvent getEvent() { return event; }
    public ControlAlertStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}

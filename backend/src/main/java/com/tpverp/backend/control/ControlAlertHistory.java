package com.tpverp.backend.control;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "control_alerta_historial")
public class ControlAlertHistory {

    @Id private UUID id;
    @Column(name = "alerta_id", nullable = false) private UUID alertId;
    @Column(name = "tienda_id", nullable = false) private UUID storeId;
    @Enumerated(EnumType.STRING) @Column(name = "estado_anterior", length = 16)
    private ControlAlertStatus previousStatus;
    @Enumerated(EnumType.STRING) @Column(name = "estado_nuevo", nullable = false, length = 16)
    private ControlAlertStatus newStatus;
    @Column(name = "comentario", length = 500) private String comment;
    @Column(name = "cambiado_por", nullable = false) private UUID changedBy;
    @Column(name = "cambiado_en", nullable = false) private Instant changedAt;

    protected ControlAlertHistory() {
    }

    public ControlAlertHistory(
            ControlAlert alert,
            ControlAlertStatus previousStatus,
            ControlAlertStatus newStatus,
            String comment,
            UUID changedBy,
            Instant changedAt) {
        this.id = UUID.randomUUID();
        this.alertId = alert.getId();
        this.storeId = alert.getStoreId();
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.comment = normalizeComment(comment);
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    public UUID getAlertId() { return alertId; }
    public ControlAlertStatus getPreviousStatus() { return previousStatus; }
    public ControlAlertStatus getNewStatus() { return newStatus; }
    public String getComment() { return comment; }
    public UUID getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }

    static String normalizeComment(String value) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.trim();
        if (normalized.length() > 500) throw new IllegalArgumentException("El comentario no puede superar 500 caracteres");
        return normalized;
    }
}

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
@Table(name = "control_alerta_trabajo_historial")
public class ControlAlertWorkHistory {

    @Id private UUID id;
    @Column(name = "alerta_id", nullable = false) private UUID alertId;
    @Column(name = "tienda_id", nullable = false) private UUID storeId;
    @Enumerated(EnumType.STRING) @Column(name = "prioridad_anterior", nullable = false, length = 16)
    private ControlAlertPriority previousPriority;
    @Enumerated(EnumType.STRING) @Column(name = "prioridad_nueva", nullable = false, length = 16)
    private ControlAlertPriority newPriority;
    @Column(name = "responsable_anterior") private UUID previousAssigneeId;
    @Column(name = "responsable_nuevo") private UUID newAssigneeId;
    @Column(name = "vence_en_anterior") private Instant previousDueAt;
    @Column(name = "vence_en_nuevo") private Instant newDueAt;
    @Column(name = "comentario", length = 500) private String comment;
    @Column(name = "cambiado_por", nullable = false) private UUID changedBy;
    @Column(name = "cambiado_en", nullable = false) private Instant changedAt;

    protected ControlAlertWorkHistory() {
    }

    public ControlAlertWorkHistory(
            ControlAlert alert,
            ControlAlert.WorkSnapshot previous,
            String comment,
            UUID changedBy,
            Instant changedAt) {
        this.id = UUID.randomUUID();
        this.alertId = alert.getId();
        this.storeId = alert.getStoreId();
        this.previousPriority = previous.priority();
        this.newPriority = alert.getPriority();
        this.previousAssigneeId = previous.assigneeId();
        this.newAssigneeId = alert.getAssigneeId();
        this.previousDueAt = previous.dueAt();
        this.newDueAt = alert.getDueAt();
        this.comment = ControlAlertHistory.normalizeComment(comment);
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    public ControlAlertPriority getPreviousPriority() { return previousPriority; }
    public ControlAlertPriority getNewPriority() { return newPriority; }
    public UUID getPreviousAssigneeId() { return previousAssigneeId; }
    public UUID getNewAssigneeId() { return newAssigneeId; }
    public Instant getPreviousDueAt() { return previousDueAt; }
    public Instant getNewDueAt() { return newDueAt; }
    public String getComment() { return comment; }
    public UUID getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }
}

package com.tpverp.backend.control;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "control_evento")
public class ControlEvent {

    @Id private UUID id;
    @Column(name = "tienda_id", nullable = false) private UUID storeId;
    @Column(name = "regla_id", nullable = false) private UUID ruleId;
    @Column(name = "regla_numero_version", nullable = false) private int ruleVersion;
    @Column(name = "regla_nombre", nullable = false, length = 160) private String ruleName;
    @Enumerated(EnumType.STRING) @Column(name = "tipo", nullable = false, length = 48) private ControlAlertType type;
    @Column(name = "origen_tipo", nullable = false, length = 32) private String sourceType;
    @Column(name = "origen_id", nullable = false) private UUID sourceId;
    @Column(name = "documento_id") private UUID documentId;
    @Column(name = "documento_numero", length = 32) private String documentNumber;
    @Column(name = "terminal_id") private UUID terminalId;
    @Column(name = "usuario_id", nullable = false) private UUID userId;
    @Column(name = "usuario_nombre", nullable = false, length = 128) private String userName;
    @Column(name = "ocurrido_en", nullable = false) private Instant occurredAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "datos", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> data;

    protected ControlEvent() {
    }

    public ControlEvent(
            UUID storeId,
            ControlRule rule,
            String sourceType,
            UUID sourceId,
            UUID documentId,
            String documentNumber,
            UUID terminalId,
            UUID userId,
            String userName,
            Instant occurredAt,
            Map<String, Object> data) {
        this.id = UUID.randomUUID();
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.ruleId = Objects.requireNonNull(rule, "rule").getId();
        this.ruleVersion = rule.getRuleVersion();
        this.ruleName = rule.getName();
        this.type = rule.getType();
        this.sourceType = required(sourceType, "sourceType", 32);
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.documentId = documentId;
        this.documentNumber = optional(documentNumber, 32);
        this.terminalId = terminalId;
        this.userId = Objects.requireNonNull(userId, "userId");
        this.userName = required(userName, "userName", 128);
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.data = Map.copyOf(data == null ? Map.of() : data);
    }

    public UUID getId() { return id; }
    public UUID getStoreId() { return storeId; }
    public UUID getRuleId() { return ruleId; }
    public int getRuleVersion() { return ruleVersion; }
    public String getRuleName() { return ruleName; }
    public ControlAlertType getType() { return type; }
    public String getSourceType() { return sourceType; }
    public UUID getSourceId() { return sourceId; }
    public UUID getDocumentId() { return documentId; }
    public String getDocumentNumber() { return documentNumber; }
    public UUID getTerminalId() { return terminalId; }
    public UUID getUserId() { return userId; }
    public String getUserName() { return userName; }
    public Instant getOccurredAt() { return occurredAt; }
    public Map<String, Object> getData() { return Map.copyOf(data); }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " es obligatorio");
        var normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(field + " es demasiado largo");
        return normalized;
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException("value es demasiado largo");
        return normalized;
    }
}

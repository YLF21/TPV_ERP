package com.tpverp.backend.control;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "control_regla_version")
public class ControlRuleVersion {

    @Id private UUID id;
    @Column(name = "regla_id", nullable = false) private UUID ruleId;
    @Column(name = "tienda_id", nullable = false) private UUID storeId;
    @Column(name = "numero_version", nullable = false) private int ruleVersion;
    @Enumerated(EnumType.STRING) @Column(name = "tipo", nullable = false, length = 48) private ControlAlertType type;
    @Column(name = "nombre", nullable = false, length = 160) private String name;
    @Column(name = "activa", nullable = false) private boolean active;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "configuracion", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> configuration;
    @Column(name = "cambiado_por", nullable = false) private UUID changedBy;
    @Column(name = "cambiado_en", nullable = false) private Instant changedAt;

    protected ControlRuleVersion() {
    }

    public ControlRuleVersion(ControlRule rule) {
        this.id = UUID.randomUUID();
        this.ruleId = rule.getId();
        this.storeId = rule.getStoreId();
        this.ruleVersion = rule.getRuleVersion();
        this.type = rule.getType();
        this.name = rule.getName();
        this.active = rule.isActive();
        this.configuration = rule.getConfiguration();
        this.changedBy = rule.getUpdatedBy();
        this.changedAt = rule.getUpdatedAt();
    }

    public UUID getRuleId() { return ruleId; }
    public int getRuleVersion() { return ruleVersion; }
    public ControlAlertType getType() { return type; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
    public Map<String, Object> getConfiguration() { return Map.copyOf(configuration); }
    public UUID getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }
}

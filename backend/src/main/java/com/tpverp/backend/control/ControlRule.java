package com.tpverp.backend.control;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "control_regla")
public class ControlRule {

    @Id
    private UUID id;
    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 48)
    private ControlAlertType type;
    @Column(name = "nombre", nullable = false, length = 160)
    private String name;
    @Column(name = "activa", nullable = false)
    private boolean active;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "configuracion", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> configuration;
    @Column(name = "numero_version", nullable = false)
    private int ruleVersion;
    @Column(name = "creado_por", nullable = false)
    private UUID createdBy;
    @Column(name = "actualizado_por", nullable = false)
    private UUID updatedBy;
    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;
    @Column(name = "actualizado_en", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected ControlRule() {
    }

    public ControlRule(
            UUID storeId,
            ControlAlertType type,
            boolean active,
            Map<String, Object> configuration,
            UUID userId,
            Instant now) {
        this.id = UUID.randomUUID();
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.type = Objects.requireNonNull(type, "type");
        this.name = type.systemName();
        this.active = active;
        this.configuration = ControlRuleConfiguration.normalize(type, configuration);
        this.ruleVersion = 1;
        this.createdBy = Objects.requireNonNull(userId, "userId");
        this.updatedBy = userId;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public void update(
            boolean active,
            Map<String, Object> configuration,
            UUID userId,
            Instant now) {
        this.name = type.systemName();
        this.active = active;
        this.configuration = ControlRuleConfiguration.normalize(type, configuration);
        this.updatedBy = Objects.requireNonNull(userId, "userId");
        this.updatedAt = Objects.requireNonNull(now, "now");
        this.ruleVersion++;
    }

    public UUID getId() { return id; }
    public UUID getStoreId() { return storeId; }
    public ControlAlertType getType() { return type; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
    public Map<String, Object> getConfiguration() { return Map.copyOf(configuration); }
    public int getRuleVersion() { return ruleVersion; }
    public UUID getCreatedBy() { return createdBy; }
    public UUID getUpdatedBy() { return updatedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

}

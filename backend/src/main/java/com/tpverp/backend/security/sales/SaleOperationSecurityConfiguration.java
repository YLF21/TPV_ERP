package com.tpverp.backend.security.sales;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "configuracion_seguridad_operacion_venta")
public class SaleOperationSecurityConfiguration {

    @Id
    @Column(name = "tienda_id")
    private UUID storeId;

    @Column(name = "config_version", nullable = false)
    private long version;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "creada_en", nullable = false)
    private Instant createdAt;

    @Column(name = "actualizada_en", nullable = false)
    private Instant updatedAt;

    @OneToMany(
            mappedBy = "configuration",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("operationCode ASC")
    private List<SaleOperationSecurityOverride> overrides = new ArrayList<>();

    protected SaleOperationSecurityConfiguration() {
    }

    public SaleOperationSecurityConfiguration(UUID storeId, Instant now) {
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public void replaceOverrides(
            Collection<OverrideValue> values,
            Instant now) {
        Objects.requireNonNull(values, "values");
        var requested = new EnumMap<SaleOperationCode, OverrideValue>(
                SaleOperationCode.class);
        values.forEach(value -> {
            if (requested.put(value.code(), value) != null) {
                throw new IllegalArgumentException(
                        "Duplicate sale operation override: " + value.code());
            }
        });
        overrides.removeIf(value -> !requested.containsKey(value.getOperationCode()));
        for (var value : requested.values()) {
            var current = overrides.stream()
                    .filter(candidate -> candidate.getOperationCode() == value.code())
                    .findFirst();
            if (current.isPresent()) {
                current.orElseThrow().update(
                        value.requirePermission(), value.requirePassword());
            } else {
                overrides.add(new SaleOperationSecurityOverride(
                        this,
                        value.code(),
                        value.requirePermission(),
                        value.requirePassword()));
            }
        }
        version++;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public UUID getStoreId() {
        return storeId;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<SaleOperationSecurityOverride> getOverrides() {
        return List.copyOf(overrides);
    }

    public record OverrideValue(
            SaleOperationCode code,
            boolean requirePermission,
            boolean requirePassword) {

        public OverrideValue {
            Objects.requireNonNull(code, "code");
        }
    }
}

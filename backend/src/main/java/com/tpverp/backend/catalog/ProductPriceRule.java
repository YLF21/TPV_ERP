package com.tpverp.backend.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "producto_regla_precio")
public class ProductPriceRule {

    @Id
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;

    @Column(nullable = false, length = 160)
    private String nombre;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<ProductPriceRuleForm.Definition> formularios = new ArrayList<>();

    @Column(name = "creado_por", nullable = false)
    private UUID createdBy;

    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;

    @Column(name = "actualizado_en", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ProductPriceRule() {
    }

    public ProductPriceRule(
            UUID companyId,
            String name,
            List<ProductPriceRuleForm.Definition> forms,
            UUID createdBy,
            Instant now) {
        id = UUID.randomUUID();
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        nombre = name(name);
        formularios = copyForms(forms);
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        createdAt = Objects.requireNonNull(now, "now");
        updatedAt = now;
    }

    public void update(
            String name,
            List<ProductPriceRuleForm.Definition> forms,
            Instant now) {
        nombre = name(name);
        formularios = copyForms(forms);
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getName() {
        return nombre;
    }

    public List<ProductPriceRuleForm.Definition> getForms() {
        return List.copyOf(formularios);
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    private static List<ProductPriceRuleForm.Definition> copyForms(
            List<ProductPriceRuleForm.Definition> forms) {
        return new ArrayList<>(ProductPriceRuleForm.validateAndCopy(forms));
    }

    private static String name(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("name es obligatorio");
        }
        String normalized = value.trim();
        if (normalized.length() > 160) {
            throw new IllegalArgumentException("name no puede superar 160 caracteres");
        }
        return normalized;
    }
}

package com.tpverp.backend.security.sales;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "configuracion_seguridad_operacion_venta_override",
        uniqueConstraints = @UniqueConstraint(
                name = "config_seguridad_operacion_venta_codigo_uq",
                columnNames = {"tienda_id", "codigo_operacion"}))
public class SaleOperationSecurityOverride {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tienda_id", nullable = false)
    private SaleOperationSecurityConfiguration configuration;

    @Enumerated(EnumType.STRING)
    @Column(name = "codigo_operacion", nullable = false, length = 64)
    private SaleOperationCode operationCode;

    @Column(name = "requiere_permiso", nullable = false)
    private boolean requirePermission;

    @Column(name = "requiere_contrasena", nullable = false)
    private boolean requirePassword;

    protected SaleOperationSecurityOverride() {
    }

    SaleOperationSecurityOverride(
            SaleOperationSecurityConfiguration configuration,
            SaleOperationCode operationCode,
            boolean requirePermission,
            boolean requirePassword) {
        this.id = UUID.randomUUID();
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.operationCode = Objects.requireNonNull(operationCode, "operationCode");
        this.requirePermission = requirePermission;
        this.requirePassword = requirePassword;
    }

    public SaleOperationCode getOperationCode() {
        return operationCode;
    }

    public boolean isRequirePermission() {
        return requirePermission;
    }

    public boolean isRequirePassword() {
        return requirePassword;
    }

    void update(boolean requirePermission, boolean requirePassword) {
        this.requirePermission = requirePermission;
        this.requirePassword = requirePassword;
    }
}

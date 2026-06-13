package com.tpverp.backend.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tienda")
public class Tienda {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "empresa_id", nullable = false)
    private Empresa empresa;

    private String nombre;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> direccion;

    @Column(name = "address_normalized_hash", nullable = false, length = 128)
    private String addressNormalizedHash;

    private String telefono;

    private String email;

    @Column(nullable = false, length = 64)
    private String timezone;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String moneda;

    @Column(nullable = false, length = 35)
    private String locale;

    @Version
    private long version;

    protected Tienda() {
    }

    public Tienda(
            Empresa empresa,
            String nombre,
            Map<String, String> direccion,
            String addressNormalizedHash,
            String timezone,
            String moneda,
            String locale) {
        this.id = UUID.randomUUID();
        this.empresa = java.util.Objects.requireNonNull(empresa, "empresa");
        this.nombre = optional(nombre);
        this.direccion = Empresa.direccion(direccion);
        this.addressNormalizedHash = required(addressNormalizedHash, "addressNormalizedHash");
        this.timezone = required(timezone, "timezone");
        this.moneda = required(moneda, "moneda").toUpperCase(java.util.Locale.ROOT);
        this.locale = required(locale, "locale");
    }

    public UUID getId() {
        return id;
    }

    public Empresa getEmpresa() {
        return empresa;
    }

    public String getNombreEfectivo() {
        return nombre == null ? empresa.getRazonSocial() : nombre;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}

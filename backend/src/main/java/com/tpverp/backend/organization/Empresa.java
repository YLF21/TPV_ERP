package com.tpverp.backend.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "empresa")
public class Empresa {

    @Id
    private UUID id;

    @Column(name = "tax_id", nullable = false, length = 64)
    private String taxId;

    @Column(name = "razon_social", nullable = false)
    private String razonSocial;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "domicilio_fiscal", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> domicilioFiscal;

    @Version
    private long version;

    protected Empresa() {
    }

    public Empresa(String taxId, String razonSocial, Map<String, String> domicilioFiscal) {
        this.id = UUID.randomUUID();
        this.taxId = required(taxId, "taxId");
        this.razonSocial = required(razonSocial, "razonSocial");
        this.domicilioFiscal = direccion(domicilioFiscal);
    }

    public UUID getId() {
        return id;
    }

    public String getRazonSocial() {
        return razonSocial;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }

    static Map<String, String> direccion(Map<String, String> value) {
        if (value == null) {
            throw new IllegalArgumentException("direccion es obligatoria");
        }
        for (String key : new String[] {"linea1", "ciudad", "codigoPostal", "provincia", "pais"}) {
            required(value.get(key), key);
        }
        return new LinkedHashMap<>(value);
    }
}

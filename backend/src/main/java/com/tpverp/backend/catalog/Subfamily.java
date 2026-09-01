package com.tpverp.backend.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "subfamilia")
public class Subfamily {

    @Id
    private UUID id;

    @Column(name = "familia_id", nullable = false)
    private UUID familyId;

    @Column(name = "subfamily_id", nullable = false, length = 32)
    private String subfamilyId;

    @Column(name = "subfamily_suffix", nullable = false, length = 3)
    private String subfamilySuffix;

    @Column(name = "subfamily_code", nullable = false, length = 6)
    private String subfamilyCode;

    @Column(nullable = false, length = 128)
    private String nombre;

    @Version
    private long version;

    protected Subfamily() {
    }

    public Subfamily(UUID familyId, String name) {
        this.id = UUID.randomUUID();
        this.familyId = Objects.requireNonNull(familyId, "familyId");
        this.nombre = CatalogText.normalized(name, "nombre");
        this.subfamilyId = Family.businessId(this.nombre, "SUBFAMILIA");
    }

    public UUID getId() {
        return id;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public String getSubfamilyId() {
        return subfamilyId;
    }

    public String getSubfamilySuffix() {
        return subfamilySuffix;
    }

    public String getSubfamilyCode() {
        return subfamilyCode;
    }

    public String getName() {
        return nombre;
    }

    public void rename(String name) {
        nombre = CatalogText.normalized(name, "nombre");
        // subfamilyId remains the legacy alias; the numeric suffix/code are immutable.
    }

    void assignCode(String familyCode, String suffix) {
        if (familyCode == null || !familyCode.matches("[0-9]{3}")) {
            throw new IllegalArgumentException("familyCode debe tener tres digitos");
        }
        if (suffix == null || !suffix.matches("[0-9]{3}")) {
            throw new IllegalArgumentException("subfamilySuffix debe tener tres digitos");
        }
        if ("000".equals(suffix)) {
            throw new IllegalArgumentException("El sufijo 000 esta reservado");
        }
        subfamilySuffix = suffix;
        subfamilyCode = familyCode + suffix;
        subfamilyId = subfamilyCode;
    }
}

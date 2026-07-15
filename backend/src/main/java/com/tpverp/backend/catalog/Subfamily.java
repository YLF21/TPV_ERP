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

    public String getName() {
        return nombre;
    }

    public void rename(String name) {
        nombre = CatalogText.normalized(name, "nombre");
        subfamilyId = Family.businessId(nombre, "SUBFAMILIA");
    }
}

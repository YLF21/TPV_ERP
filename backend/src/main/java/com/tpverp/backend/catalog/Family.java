package com.tpverp.backend.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "familia")
public class Family {

    @Id
    private UUID id;

    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Column(name = "family_id", nullable = false, length = 32)
    private String familyId;

    @Column(nullable = false, length = 128)
    private String nombre;

    @Column(nullable = false)
    private boolean predeterminada;

    @Version
    private long version;

    protected Family() {
    }

    public Family(UUID storeId, String name, boolean defaultFamily) {
        this.id = UUID.randomUUID();
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.nombre = CatalogText.normalized(name, "nombre");
        this.familyId = defaultFamily ? "GENERAL" : businessId(this.nombre, "FAMILIA");
        this.predeterminada = defaultFamily;
        if (defaultFamily && !"GENERAL".equals(nombre)) {
            throw new IllegalArgumentException("La familia predeterminada debe llamarse GENERAL");
        }
    }

    public static Family general(UUID storeId) {
        return new Family(storeId, "GENERAL", true);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getFamilyId() {
        return familyId;
    }

    public String getName() {
        return nombre;
    }

    public boolean isDefaultFamily() {
        return predeterminada;
    }

    public void rename(String name) {
        if (predeterminada) {
            throw new IllegalStateException("La familia GENERAL esta protegida");
        }
        nombre = CatalogText.normalized(name, "nombre");
        familyId = businessId(nombre, "FAMILIA");
    }

    public void requireDeletable() {
        if (predeterminada) {
            throw new IllegalStateException("La familia GENERAL esta protegida");
        }
    }

    static String businessId(String value, String fallback) {
        String normalized = CatalogText.normalized(value, fallback)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            normalized = fallback;
        }
        return normalized.length() <= 32 ? normalized : normalized.substring(0, 32);
    }
}

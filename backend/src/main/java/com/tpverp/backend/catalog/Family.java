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

    @Column(name = "family_code", nullable = false, length = 3)
    private String familyCode;

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
        this.familyCode = defaultFamily ? "000" : null;
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

    public String getFamilyCode() {
        return familyCode;
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
        // familyId remains the legacy alias used by existing clients. The
        // stable familyCode is assigned once by CatalogService.
    }

    public void requireDeletable() {
        if (predeterminada) {
            throw new IllegalStateException("La familia GENERAL esta protegida");
        }
    }

    void assignCode(String code) {
        if (predeterminada && !"000".equals(code)) {
            throw new IllegalArgumentException("La familia GENERAL debe usar el codigo 000");
        }
        if (!predeterminada && "000".equals(code)) {
            throw new IllegalArgumentException("El codigo 000 esta reservado para GENERAL");
        }
        if (code == null || !code.matches("[0-9]{3}")) {
            throw new IllegalArgumentException("familyCode debe tener tres digitos");
        }
        familyCode = code;
        if (!predeterminada) {
            // New records use the stable numeric code in the legacy alias too;
            // migrated records retain their historical alias unchanged.
            familyId = code;
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

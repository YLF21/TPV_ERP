package com.tpverp.backend.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "almacen")
public class Warehouse {

    @Id
    private UUID id;

    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Column(nullable = false, length = 128)
    private String nombre;

    @Column(name = "direccion", length = 512)
    private String address;

    @Column(name = "notas", columnDefinition = "text")
    private String notes;

    @Column(nullable = false)
    private boolean predeterminado;

    @Column(nullable = false)
    private boolean activo = true;

    @Version
    private long version;

    protected Warehouse() {
    }

    public Warehouse(UUID storeId, String name) {
        this(storeId, name, false);
    }

    public Warehouse(UUID storeId, String name, String address, String notes) {
        this(storeId, name, false);
        updateDetails(name, address, notes);
    }

    private Warehouse(UUID storeId, String name, boolean defaultWarehouse) {
        this.id = UUID.randomUUID();
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.nombre = CatalogText.normalized(name, "nombre");
        this.predeterminado = defaultWarehouse;
    }

    public static Warehouse general(UUID storeId) {
        return new Warehouse(storeId, "GENERAL", true);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getName() {
        return nombre;
    }

    public String getAddress() { return address; }

    public String getNotes() { return notes; }

    public boolean isDefaultWarehouse() {
        return predeterminado;
    }

    public boolean isActive() {
        return activo;
    }

    public void rename(String name) {
        requireEditable();
        nombre = CatalogText.normalized(name, "nombre");
    }

    public void updateDetails(String name, String address, String notes) {
        if (predeterminado) {
            if (!nombre.equals(CatalogText.normalized(name, "nombre"))) requireEditable();
        } else rename(name);
        if (!predeterminado) this.address = normalizedOptional(address, 512, "direccion");
        this.notes = normalizedOptional(notes, 4000, "notas");
    }

    private static String normalizedOptional(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(field + " demasiado largo");
        return normalized;
    }

    public void deactivate(long totalStock) {
        deactivate(BigDecimal.valueOf(totalStock));
    }

    public void deactivate(BigDecimal totalStock) {
        requireEditable();
        if (totalStock.compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalStateException("message.warehouse.only_zero_stock_can_deactivate");
        }
        activo = false;
    }

    public void activate() {
        activo = true;
    }

    private void requireEditable() {
        if (predeterminado) {
            throw new IllegalStateException("El almacen GENERAL esta protegido");
        }
    }
}

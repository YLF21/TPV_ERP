package com.tpverp.backend.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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

    public void deactivate(long totalStock) {
        requireEditable();
        if (totalStock != 0) {
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

package com.tpverp.backend.catalog;

import com.tpverp.backend.party.Supplier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "producto_proveedor")
public class ProductSupplier {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "producto_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proveedor_id", nullable = false)
    private Supplier supplier;

    @Column(name = "referencia_proveedor", length = 128)
    private String supplierReference;

    @Column(name = "ultima_fecha_entrada")
    private LocalDate lastEntryDate;

    @Version
    private long version;

    protected ProductSupplier() {
    }

    public ProductSupplier(Product product, Supplier supplier, String supplierReference) {
        id = UUID.randomUUID();
        this.product = Objects.requireNonNull(product, "producto");
        this.supplier = Objects.requireNonNull(supplier, "proveedor");
        changeReference(supplierReference);
    }

    public void changeReference(String reference) {
        String normalized = reference == null || reference.isBlank()
                ? null
                : reference.trim().toUpperCase(Locale.ROOT);
        if (normalized != null && normalized.length() > 128) {
            throw new IllegalArgumentException(
                    "La referencia del proveedor no puede superar 128 caracteres");
        }
        supplierReference = normalized;
    }

    public void registerEntry(LocalDate date) {
        Objects.requireNonNull(date, "fechaEntrada");
        // Conserva la fecha mas reciente aunque se confirme despues un documento antiguo.
        if (lastEntryDate == null || date.isAfter(lastEntryDate)) {
            lastEntryDate = date;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return product.getId();
    }

    public Supplier getSupplier() {
        return supplier;
    }

    public String getSupplierReference() {
        return supplierReference;
    }

    public LocalDate getLastEntryDate() {
        return lastEntryDate;
    }
}

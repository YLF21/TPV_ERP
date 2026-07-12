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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
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

    @Column(name = "ultimo_proveedor", nullable = false)
    private boolean lastSupplier;

    @Column(nullable = false)
    private boolean principal;

    @Column(name = "precio_compra_bruto", precision = 19, scale = 2)
    private BigDecimal grossPurchasePrice;

    @Column(name = "descuento_compra", precision = 5, scale = 2)
    private BigDecimal purchaseDiscount;

    @Column(name = "ultima_entrada_en")
    private Instant lastEntryAt;

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

    public void registerEntry(Instant entryAt, BigDecimal grossPrice, BigDecimal discount) {
        Objects.requireNonNull(entryAt, "entryAt");
        if (lastEntryAt == null || !entryAt.isBefore(lastEntryAt)) {
            grossPurchasePrice = nonNegative(grossPrice, "grossPurchasePrice");
            purchaseDiscount = percentage(discount);
            lastEntryAt = entryAt;
            lastSupplier = true;
        }
    }

    public void clearLastSupplier() {
        lastSupplier = false;
    }

    public void makePrincipal() {
        principal = true;
    }

    public void clearPrincipal() {
        principal = false;
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

    public boolean isLastSupplier() { return lastSupplier; }
    public boolean isPrincipal() { return principal; }
    public BigDecimal getGrossPurchasePrice() { return grossPurchasePrice; }
    public BigDecimal getPurchaseDiscount() { return purchaseDiscount; }
    public BigDecimal getNetPurchasePrice() {
        if (grossPurchasePrice == null) {
            return null;
        }
        BigDecimal discount = purchaseDiscount == null ? BigDecimal.ZERO : purchaseDiscount;
        return grossPurchasePrice.multiply(BigDecimal.ONE.subtract(discount.movePointLeft(2)))
                .setScale(2, RoundingMode.HALF_UP);
    }
    public Instant getLastEntryAt() { return lastEntryAt; }

    private static BigDecimal nonNegative(BigDecimal value, String field) {
        BigDecimal normalized = Objects.requireNonNull(value, field).setScale(2, RoundingMode.HALF_UP);
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException(field + " no puede ser negativo");
        }
        return normalized;
    }

    private static BigDecimal percentage(BigDecimal value) {
        BigDecimal normalized = Objects.requireNonNull(value, "purchaseDiscount").setScale(2, RoundingMode.HALF_UP);
        if (normalized.signum() < 0 || normalized.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("purchaseDiscount debe estar entre 0 y 100");
        }
        return normalized;
    }
}

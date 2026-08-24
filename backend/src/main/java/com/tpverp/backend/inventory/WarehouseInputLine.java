package com.tpverp.backend.inventory;

import com.tpverp.backend.document.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "entrada_almacen_linea")
public class WarehouseInputLine {

    @Id
    private UUID id;

    @Column(name = "entrada_id", nullable = false)
    private UUID inputId;

    @Column(name = "producto_id", nullable = false)
    private UUID productId;

    @Column(name = "posicion", nullable = false)
    private int position;

    @Column(name = "nombre_producto", nullable = false, length = 255)
    private String productName;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal cantidad;

    @Column(name = "precio_unitario_compra", nullable = false, precision = 19, scale = 2)
    private BigDecimal purchaseUnitPrice = BigDecimal.ZERO;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal descuento = BigDecimal.ZERO;

    @Column(name = "precio_personalizado", nullable = false)
    private boolean priceOverridden;

    @Version
    private long version;

    protected WarehouseInputLine() {
    }

    public WarehouseInputLine(UUID inputId, UUID productId, int quantity) {
        this(inputId, productId, BigDecimal.valueOf(quantity), BigDecimal.ZERO, BigDecimal.ZERO, false, productId.toString());
    }

    public WarehouseInputLine(UUID inputId, UUID productId, int quantity, BigDecimal purchaseUnitPrice) {
        this(inputId, productId, BigDecimal.valueOf(quantity), purchaseUnitPrice, BigDecimal.ZERO, false, productId.toString());
    }

    public WarehouseInputLine(
            UUID inputId,
            UUID productId,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            boolean priceOverridden) {
        this(inputId, productId, quantity, unitPrice, discount, priceOverridden, productId.toString());
    }

    public WarehouseInputLine(
            UUID inputId,
            UUID productId,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            boolean priceOverridden,
            String productName) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser positiva");
        }
        this.id = UUID.randomUUID();
        this.inputId = Objects.requireNonNull(inputId, "inputId");
        this.productId = Objects.requireNonNull(productId, "productId");
        this.productName = requiredProductName(productName);
        this.cantidad = quantity(quantity);
        this.purchaseUnitPrice = Money.euros(Objects.requireNonNull(unitPrice, "unitPrice"));
        if (this.purchaseUnitPrice.signum() < 0) {
            throw new IllegalArgumentException("El precio de compra no puede ser negativo");
        }
        this.descuento = percent(discount);
        this.priceOverridden = priceOverridden;
    }

    public UUID getProductId() {
        return productId;
    }

    public int getPosition() {
        return position;
    }

    void assignPosition(int position) {
        if (position <= 0) throw new IllegalArgumentException("position debe ser positiva");
        this.position = position;
    }

    public String getProductName() {
        return productName;
    }

    public BigDecimal getQuantity() {
        return cantidad;
    }

    public BigDecimal getPurchaseUnitPrice() {
        return purchaseUnitPrice;
    }

    public BigDecimal getPurchaseTotal() {
        return getTotal();
    }

    public BigDecimal getDiscount() {
        return descuento;
    }

    public boolean isPriceOverridden() {
        return priceOverridden;
    }

    public BigDecimal getSubtotal() {
        return Money.euros(purchaseUnitPrice.multiply(cantidad));
    }

    public BigDecimal getTotal() {
        return Money.euros(getSubtotal().multiply(
                BigDecimal.ONE.subtract(descuento.movePointLeft(2))));
    }

    void snapshotPurchaseUnitPrice(BigDecimal purchaseUnitPrice) {
        var normalized = Money.euros(Objects.requireNonNull(purchaseUnitPrice, "purchaseUnitPrice"));
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException("El precio de compra no puede ser negativo");
        }
        this.purchaseUnitPrice = normalized;
    }

    private static BigDecimal quantity(BigDecimal value) {
        Objects.requireNonNull(value, "quantity");
        if (value.signum() <= 0 || value.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException("La cantidad debe ser positiva con hasta 3 decimales");
        }
        return value.setScale(3, Money.ROUNDING);
    }

    private static BigDecimal percent(BigDecimal value) {
        var normalized = Objects.requireNonNullElse(value, BigDecimal.ZERO).setScale(2, Money.ROUNDING);
        if (normalized.signum() < 0 || normalized.compareTo(new BigDecimal("100.00")) > 0) {
            throw new IllegalArgumentException("El descuento debe estar entre 0 y 100");
        }
        return normalized;
    }

    private static String requiredProductName(String value) {
        var normalized = Objects.requireNonNull(value, "productName").trim();
        if (normalized.isEmpty() || normalized.length() > 255) {
            throw new IllegalArgumentException("El nombre de la linea debe tener entre 1 y 255 caracteres");
        }
        return normalized;
    }
}

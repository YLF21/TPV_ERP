package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.document.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "traspaso_almacen_linea")
public class WarehouseTransferLine {
    @Id private UUID id;
    @Column(name = "traspaso_id", nullable = false) private UUID transferId;
    @Column(name = "producto_id", nullable = false) private UUID productId;
    @Column(name = "codigo", nullable = false) private String code;
    @Column(name = "codigo_barras") private String barcode;
    @Column(name = "nombre", nullable = false) private String name;
    @Column(name = "cantidad", nullable = false, precision = 19, scale = 3) private BigDecimal quantity;
    @Column(name = "posicion", nullable = false) private int position;
    @Column(name = "precio_unitario", nullable = false, precision = 20, scale = 3) private BigDecimal unitPrice;
    @Column(name = "descuento", nullable = false, precision = 5, scale = 2) private BigDecimal discount;
    @Column(name = "precio_personalizado", nullable = false) private boolean priceOverridden;

    protected WarehouseTransferLine() {}
    public WarehouseTransferLine(UUID transferId, Product product, BigDecimal quantity) {
        this(transferId, product, quantity, BigDecimal.ZERO, BigDecimal.ZERO, false, null, 1);
    }
    public WarehouseTransferLine(UUID transferId, Product product, BigDecimal quantity, BigDecimal unitPrice,
            BigDecimal discount, boolean priceOverridden, String productName, int position) {
        this.id = UUID.randomUUID();
        this.transferId = transferId;
        this.productId = product.getId();
        this.code = product.getCode();
        this.barcode = product.getBarcode();
        this.name = productName == null || productName.isBlank() ? product.getName() : productName.trim();
        if (name == null || name.isBlank() || name.length() > 255) {
            throw new IllegalArgumentException("El nombre de la linea debe tener entre 1 y 255 caracteres");
        }
        if (quantity == null || quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 3
                || quantity.precision() - quantity.scale() > 16) {
            throw new IllegalArgumentException("Cantidad de traspaso inválida");
        }
        this.quantity = quantity.setScale(3);
        this.unitPrice = Money.exactUnitPrice(unitPrice);
        if (this.unitPrice.signum() < 0) throw new IllegalArgumentException("El precio no puede ser negativo");
        this.discount = percent(discount);
        this.priceOverridden = priceOverridden;
        this.position = position;
    }
    static BigDecimal percent(BigDecimal value) {
        var normalized = value == null ? BigDecimal.ZERO : value;
        if (normalized.signum() < 0 || normalized.compareTo(new BigDecimal("100")) > 0
                || normalized.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("El descuento debe estar entre 0 y 100 con hasta 2 decimales");
        }
        return normalized.setScale(2);
    }
    public UUID getId() { return id; }
    public UUID getTransferId() { return transferId; }
    public UUID getProductId() { return productId; }
    public String getCode() { return code; }
    public String getBarcode() { return barcode; }
    public String getName() { return name; }
    public BigDecimal getQuantity() { return quantity; }
    public int getPosition() { return position; }
    public String getProductName() { return name; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getDiscount() { return discount; }
    public boolean isPriceOverridden() { return priceOverridden; }
    public BigDecimal getSubtotal() { return Money.euros(unitPrice.multiply(quantity)); }
    public BigDecimal getTotal() { return Money.euros(getSubtotal().multiply(BigDecimal.ONE.subtract(discount.movePointLeft(2)))); }
}

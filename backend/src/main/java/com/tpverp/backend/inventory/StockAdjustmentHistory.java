package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ajuste_stock_historial")
public class StockAdjustmentHistory {
    @Id @Column(name = "movimiento_id") private UUID movementId;
    @Column(name = "tienda_id", nullable = false) private UUID storeId;
    @Column(name = "almacen_id", nullable = false) private UUID warehouseId;
    @Column(name = "producto_id", nullable = false) private UUID productId;
    @Column(name = "codigo", nullable = false) private String code;
    @Column(name = "codigo_barras") private String barcode;
    @Column(name = "nombre", nullable = false) private String name;
    @Column(name = "cantidad_anterior", precision = 19, scale = 3)
    private BigDecimal previousQuantity;
    @Column(name = "cantidad_ajuste", nullable = false, precision = 19, scale = 3)
    private BigDecimal adjustmentQuantity;
    @Column(name = "cantidad_posterior", precision = 19, scale = 3)
    private BigDecimal nextQuantity;
    @Column(name = "motivo", nullable = false, columnDefinition = "text") private String reason;
    @Column(name = "creado_en", nullable = false) private Instant createdAt;

    @jakarta.persistence.Transient
    private String userName;

    protected StockAdjustmentHistory() {}

    public StockAdjustmentHistory(UUID storeId, StockMovement movement, Product product,
                                  BigDecimal previousQuantity, BigDecimal nextQuantity) {
        this.movementId = movement.getId();
        this.storeId = storeId;
        this.warehouseId = movement.getWarehouseId();
        this.productId = product.getId();
        this.code = product.getCode();
        this.barcode = product.getBarcode();
        this.name = product.getName();
        this.previousQuantity = previousQuantity;
        this.adjustmentQuantity = movement.getQuantity();
        this.nextQuantity = nextQuantity;
        this.reason = movement.getReason();
        this.createdAt = movement.getCreatedAt();
    }

    public String getUserName() { return userName; }
    void setUserName(String userName) { this.userName = userName; }

    public UUID getMovementId() { return movementId; }
    public UUID getStoreId() { return storeId; }
    public UUID getWarehouseId() { return warehouseId; }
    public UUID getProductId() { return productId; }
    public String getCode() { return code; }
    public String getBarcode() { return barcode; }
    public String getName() { return name; }
    public BigDecimal getPreviousQuantity() { return previousQuantity; }
    public BigDecimal getAdjustmentQuantity() { return adjustmentQuantity; }
    public BigDecimal getNextQuantity() { return nextQuantity; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}

package com.tpverp.backend.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "movimiento_stock")
public class StockMovement {

    @Id
    private UUID id;

    @Column(name = "producto_id", nullable = false)
    private UUID productId;

    @Column(name = "almacen_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "usuario_id", nullable = false)
    private UUID userId;

    @Column(name = "documento_id")
    private UUID documentId;

    @Column(name = "salida_almacen_id")
    private UUID warehouseOutputId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StockMovementType tipo;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal cantidad;

    @Column(columnDefinition = "text")
    private String motivo;

    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;

    @Column(name = "compensacion_de_id")
    private UUID compensationOfId;

    @Column(name = "transferencia_id")
    private UUID transferId;

    @Version
    private long version;

    protected StockMovement() {
    }

    private StockMovement(
            UUID productId,
            UUID warehouseId,
            UUID userId,
            StockMovementType type,
            BigDecimal quantity,
            String reason,
            UUID transferId,
            Instant createdAt) {
        if (quantity(quantity).signum() == 0) {
            throw new IllegalArgumentException("El movimiento no puede ser cero");
        }
        this.id = UUID.randomUUID();
        this.productId = Objects.requireNonNull(productId, "productId");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.tipo = Objects.requireNonNull(type, "type");
        this.cantidad = quantity(quantity);
        this.motivo = optional(reason);
        this.transferId = transferId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static StockMovement adjustment(
            UUID productId, UUID warehouseId, UUID userId, int quantity, String reason, Instant createdAt) {
        return adjustment(productId, warehouseId, userId, BigDecimal.valueOf(quantity), reason, createdAt);
    }

    public static StockMovement adjustment(
            UUID productId, UUID warehouseId, UUID userId, BigDecimal quantity, String reason, Instant createdAt) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("El ajuste necesita un motivo");
        }
        return new StockMovement(
                productId, warehouseId, userId, StockMovementType.AJUSTE,
                quantity, reason, null, createdAt);
    }

    public static StockMovement transferOut(
            UUID productId, UUID warehouseId, UUID userId, int quantity, UUID transferId, Instant createdAt) {
        return transferOut(productId, warehouseId, userId, BigDecimal.valueOf(quantity), transferId, createdAt);
    }

    public static StockMovement transferOut(
            UUID productId, UUID warehouseId, UUID userId, BigDecimal quantity, UUID transferId, Instant createdAt) {
        return new StockMovement(
                productId, warehouseId, userId, StockMovementType.TRANSFERENCIA_SALIDA,
                positive(quantity).negate(), null, Objects.requireNonNull(transferId, "transferId"), createdAt);
    }

    public static StockMovement transferIn(
            UUID productId, UUID warehouseId, UUID userId, int quantity, UUID transferId, Instant createdAt) {
        return transferIn(productId, warehouseId, userId, BigDecimal.valueOf(quantity), transferId, createdAt);
    }

    public static StockMovement transferIn(
            UUID productId, UUID warehouseId, UUID userId, BigDecimal quantity, UUID transferId, Instant createdAt) {
        return new StockMovement(
                productId, warehouseId, userId, StockMovementType.TRANSFERENCIA_ENTRADA,
                positive(quantity), null, Objects.requireNonNull(transferId, "transferId"), createdAt);
    }

    public static StockMovement document(
            UUID productId,
            UUID warehouseId,
            UUID userId,
            UUID documentId,
            StockMovementType type,
            int quantity,
            Instant createdAt) {
        return document(productId, warehouseId, userId, documentId, type, BigDecimal.valueOf(quantity), createdAt);
    }

    public static StockMovement document(
            UUID productId,
            UUID warehouseId,
            UUID userId,
            UUID documentId,
            StockMovementType type,
            BigDecimal quantity,
            Instant createdAt) {
        var movement = new StockMovement(
                productId, warehouseId, userId, type, quantity, null, null, createdAt);
        movement.documentId = Objects.requireNonNull(documentId, "documentId");
        return movement;
    }

    public static StockMovement compensation(
            StockMovement original, UUID userId, Instant createdAt) {
        Objects.requireNonNull(original, "original");
        var movement = document(
                original.productId,
                original.warehouseId,
                userId,
                original.documentId,
                StockMovementType.ANULACION,
                original.cantidad.negate(),
                createdAt);
        movement.compensationOfId = original.id;
        return movement;
    }

    public static StockMovement warehouseOutput(
            UUID productId,
            UUID warehouseId,
            UUID userId,
            UUID outputId,
            int quantity,
            Instant createdAt) {
        return warehouseOutput(productId, warehouseId, userId, outputId, BigDecimal.valueOf(quantity), createdAt);
    }

    public static StockMovement warehouseOutput(
            UUID productId,
            UUID warehouseId,
            UUID userId,
            UUID outputId,
            BigDecimal quantity,
            Instant createdAt) {
        var movement = new StockMovement(
                productId,
                warehouseId,
                userId,
                StockMovementType.SALIDA_ALMACEN,
                positive(quantity).negate(),
                null,
                null,
                createdAt);
        movement.warehouseOutputId = Objects.requireNonNull(outputId, "outputId");
        return movement;
    }

    public UUID getId() {
        return id;
    }

    public BigDecimal getQuantity() {
        return cantidad;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public UUID getUserId() {
        return userId;
    }

    public StockMovementType getType() {
        return tipo;
    }

    public String getReason() {
        return motivo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getWarehouseOutputId() {
        return warehouseOutputId;
    }

    public UUID getCompensationOfId() {
        return compensationOfId;
    }

    private static BigDecimal positive(BigDecimal quantity) {
        var value = quantity(quantity);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser positiva");
        }
        return value;
    }

    private static BigDecimal quantity(BigDecimal value) {
        Objects.requireNonNull(value, "cantidad");
        if (value.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException("message.inventory.quantity_scale");
        }
        return value.setScale(3);
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

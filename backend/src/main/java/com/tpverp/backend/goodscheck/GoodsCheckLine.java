package com.tpverp.backend.goodscheck;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "comprobacion_mercancia_linea", uniqueConstraints = @UniqueConstraint(
        columnNames = {"comprobacion_id", "producto_id"}))
public class GoodsCheckLine {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comprobacion_id", nullable = false)
    private GoodsCheck comprobacion;
    @Column(name = "producto_id", nullable = false)
    private UUID productoId;
    @Column(name = "cantidad_esperada", nullable = false, precision = 19, scale = 3)
    private BigDecimal cantidadEsperada;
    @Column(name = "cantidad_registrada", nullable = false, precision = 19, scale = 3)
    private BigDecimal cantidadRegistrada = BigDecimal.ZERO.setScale(3);
    @Column(name = "ultimo_usuario_id")
    private UUID ultimoUsuarioId;
    @Column(name = "terminal_id")
    private UUID terminalId;
    @Column(name = "actualizado_en")
    private Instant actualizadoEn;
    @Version
    private long version;

    protected GoodsCheckLine() {
    }

    public GoodsCheckLine(GoodsCheck comprobacion, UUID productoId, BigDecimal cantidadEsperada) {
        var expected = quantity(cantidadEsperada);
        if (expected.signum() == 0) {
            throw new IllegalArgumentException("message.goods_check.expected_quantity_required");
        }
        this.id = UUID.randomUUID();
        this.comprobacion = Objects.requireNonNull(comprobacion, "comprobacion");
        this.productoId = Objects.requireNonNull(productoId, "productoId");
        this.cantidadEsperada = expected;
    }

    public UUID getProductoId() {
        return productoId;
    }

    public BigDecimal getCantidadEsperada() {
        return cantidadEsperada;
    }

    public BigDecimal getCantidadRegistrada() {
        return cantidadRegistrada;
    }

    public void addQuantity(BigDecimal quantity, UUID userId, UUID terminalId, Instant now) {
        var next = cantidadRegistrada.add(quantity(quantity));
        if (next.signum() < 0) {
            throw new IllegalArgumentException("message.goods_check.registered_quantity_negative");
        }
        cantidadRegistrada = next;
        ultimoUsuarioId = Objects.requireNonNull(userId, "userId");
        this.terminalId = terminalId;
        actualizadoEn = Objects.requireNonNull(now, "now");
    }
    // Accumulates scan corrections while protecting the registered total from going below zero.

    private static BigDecimal quantity(BigDecimal value) {
        Objects.requireNonNull(value, "quantity");
        if (value.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException("message.document.quantity_scale");
        }
        return value.setScale(3);
    }
}

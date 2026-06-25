package com.tpverp.backend.cash;

import com.tpverp.backend.document.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "movimiento_caja_denominacion")
public class CashMovementDenomination {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "movimiento_caja_id", nullable = false)
    private CashMovement movement;

    @Column(name = "denominacion", nullable = false, precision = 19, scale = 2)
    private BigDecimal denomination;

    @Column(name = "cantidad", nullable = false)
    private int quantity;

    protected CashMovementDenomination() {
    }

    CashMovementDenomination(CashMovement movement, BigDecimal denomination, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("cantidad debe ser positiva");
        }
        this.id = UUID.randomUUID();
        this.movement = Objects.requireNonNull(movement, "movement");
        this.denomination = Money.euros(denomination);
        this.quantity = quantity;
    }

    BigDecimal subtotal() {
        return Money.euros(denomination.multiply(BigDecimal.valueOf(quantity)));
    }
}

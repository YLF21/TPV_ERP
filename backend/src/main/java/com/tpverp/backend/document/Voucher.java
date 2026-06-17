package com.tpverp.backend.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "vale", uniqueConstraints = @UniqueConstraint(
        columnNames = {"tienda_id", "codigo"}))
public class Voucher {

    @Id
    private UUID id;
    @Column(name = "tienda_id", nullable = false)
    private UUID tiendaId;
    @Column(name = "codigo", nullable = false, length = 32)
    private String code;
    @Column(name = "importe_inicial", nullable = false, precision = 19, scale = 2)
    private BigDecimal initialAmount;
    @Column(name = "saldo", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VoucherStatus status;
    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tickets_origen", nullable = false, columnDefinition = "jsonb")
    private List<String> originTickets = new ArrayList<>();
    @Version
    private long version;

    protected Voucher() {
    }

    public Voucher(
            UUID storeId, String code, BigDecimal amount,
            List<String> originTickets, Instant createdAt) {
        id = UUID.randomUUID();
        tiendaId = Objects.requireNonNull(storeId, "storeId");
        this.code = required(code, "codigo");
        initialAmount = positive(amount);
        balance = initialAmount;
        this.originTickets = List.copyOf(originTickets);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        status = VoucherStatus.ACTIVE;
    }

    public UUID id() {
        return id;
    }

    public String code() {
        return code;
    }

    public BigDecimal initialAmount() {
        return initialAmount;
    }

    public BigDecimal balance() {
        return balance;
    }

    public VoucherStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<String> originTickets() {
        return List.copyOf(originTickets);
    }

    public BigDecimal consume(BigDecimal amount) {
        if (status != VoucherStatus.ACTIVE) {
            throw new IllegalStateException("vale no activo");
        }
        var normalized = positive(amount);
        var consumed = normalized.min(balance);
        balance = Money.euros(balance.subtract(consumed));
        if (balance.signum() == 0) {
            status = VoucherStatus.CONSUMED;
        }
        return consumed;
    }
    // Consume saldo sin permitir importes negativos ni dejar saldos por debajo de cero.

    public void closeForReplacement() {
        balance = Money.euros(BigDecimal.ZERO);
        status = VoucherStatus.CONSUMED;
    }
    // Cierra el vale original cuando el sobrante se reemite con un codigo nuevo.

    private static BigDecimal positive(BigDecimal value) {
        var amount = Money.euros(value);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("importe debe ser positivo");
        }
        return amount;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}

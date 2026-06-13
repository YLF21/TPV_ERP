package com.tpverp.backend.party;

import com.tpverp.backend.security.domain.Usuario;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "movimiento_saldo_socio")
public class MemberBalanceMovement {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cliente_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario user;

    @Column(name = "documento_id")
    private UUID documentId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal importe;

    @Column(nullable = false, columnDefinition = "text")
    private String motivo;

    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "compensacion_de_id")
    private MemberBalanceMovement compensationOf;

    @Version
    private long version;

    protected MemberBalanceMovement() {
    }

    public MemberBalanceMovement(
            Customer customer,
            Usuario user,
            UUID documentId,
            BigDecimal amount,
            String reason,
            Instant createdAt,
            MemberBalanceMovement compensationOf) {
        BigDecimal normalizedAmount = PartyValues.money(amount);
        if (normalizedAmount.signum() == 0) {
            throw new IllegalArgumentException("importe no puede ser cero");
        }
        this.id = UUID.randomUUID();
        this.customer = Objects.requireNonNull(customer, "cliente");
        this.user = Objects.requireNonNull(user, "usuario");
        this.documentId = documentId;
        this.importe = normalizedAmount;
        this.motivo = PartyValues.required(reason, "motivo");
        this.createdAt = Objects.requireNonNull(createdAt, "creadoEn");
        this.compensationOf = compensationOf;
    }

    public UUID getId() {
        return id;
    }

    public BigDecimal getAmount() {
        return importe;
    }

    public String getReason() {
        return motivo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

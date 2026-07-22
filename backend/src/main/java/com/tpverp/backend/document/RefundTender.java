package com.tpverp.backend.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "documento_devolucion_pago")
public class RefundTender {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "documento_devolucion_id", nullable = false)
    private CommercialDocument refundDocument;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 16)
    private RefundTenderType type;

    @Column(name = "importe", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "documento_pago_original_id")
    private UUID originalPaymentId;

    @Column(name = "terminal_operacion_id", unique = true)
    private UUID terminalOperationId;

    @Column(name = "referencia", length = 128)
    private String reference;

    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;

    protected RefundTender() {
    }

    public RefundTender(
            CommercialDocument refundDocument,
            RefundTenderType type,
            BigDecimal amount,
            UUID originalPaymentId,
            UUID terminalOperationId,
            String reference,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.refundDocument = Objects.requireNonNull(refundDocument, "refundDocument");
        this.type = Objects.requireNonNull(type, "type");
        this.amount = Money.euros(amount);
        if (this.amount.signum() <= 0) {
            throw new IllegalArgumentException("El importe devuelto debe ser positivo");
        }
        if (type == RefundTenderType.CARD && terminalOperationId == null) {
            throw new IllegalArgumentException("La devolucion con tarjeta requiere operacion de datafono");
        }
        if (type != RefundTenderType.CARD && terminalOperationId != null) {
            throw new IllegalArgumentException("Solo la devolucion con tarjeta admite operacion de datafono");
        }
        this.originalPaymentId = originalPaymentId;
        this.terminalOperationId = terminalOperationId;
        this.reference = reference == null || reference.isBlank() ? null : reference.trim();
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public UUID getId() { return id; }
    public CommercialDocument getRefundDocument() { return refundDocument; }
    public RefundTenderType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public UUID getOriginalPaymentId() { return originalPaymentId; }
    public UUID getTerminalOperationId() { return terminalOperationId; }
    public String getReference() { return reference; }
    public Instant getCreatedAt() { return createdAt; }
}

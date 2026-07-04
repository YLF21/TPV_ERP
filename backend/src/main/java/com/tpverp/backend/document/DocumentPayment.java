package com.tpverp.backend.document;

import com.tpverp.backend.terminal.PaymentCardMode;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "documento_pago", uniqueConstraints = @UniqueConstraint(
        columnNames = {"documento_id", "posicion"}))
public class DocumentPayment {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "documento_id", nullable = false)
    private CommercialDocument documento;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "metodo_pago_id", nullable = false)
    private PaymentMethod metodoPago;
    @Column(nullable = false)
    private int posicion;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal importe;
    @Column(nullable = false)
    private boolean principal;
    @Column(precision = 19, scale = 2)
    private BigDecimal entregado;
    @Column(precision = 19, scale = 2)
    private BigDecimal cambio;
    @Column(name = "codigo_vale", length = 32)
    private String voucherCode;
    @Column(length = 128)
    private String referencia;
    @Enumerated(EnumType.STRING)
    @Column(name = "terminal_pago_modo", length = 16)
    private PaymentCardMode cardMode;
    @Enumerated(EnumType.STRING)
    @Column(name = "terminal_pago_provider", length = 32)
    private PaymentTerminalProvider paymentTerminalProvider;
    @Enumerated(EnumType.STRING)
    @Column(name = "terminal_pago_estado", length = 16)
    private PaymentTerminalOperationStatus paymentTerminalStatus;
    @Column(name = "autorizacion_tarjeta", length = 64)
    private String cardAuthorizationCode;
    @Column(name = "terminal_cobro_id")
    private UUID paymentTerminalId;
    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;
    @Version
    private long version;

    protected DocumentPayment() {
    }

    public DocumentPayment(
            CommercialDocument documento,
            PaymentMethod metodoPago,
            int posicion,
            BigDecimal importe,
            boolean principal,
            BigDecimal entregado,
            BigDecimal cambio,
            String voucherCode,
            String referencia,
            Instant creadoEn,
            PaymentCardMode cardMode,
            PaymentTerminalProvider paymentTerminalProvider,
            PaymentTerminalOperationStatus paymentTerminalStatus,
            String cardAuthorizationCode,
            UUID paymentTerminalId) {
        if (posicion < 1) {
            throw new IllegalArgumentException("message.document.position_must_be_positive");
        }
        this.id = UUID.randomUUID();
        this.documento = Objects.requireNonNull(documento, "documento");
        this.metodoPago = Objects.requireNonNull(metodoPago, "metodoPago");
        this.posicion = posicion;
        this.importe = nonNegative(importe, "importe");
        this.principal = principal;
        this.entregado = nullableMoney(entregado);
        this.cambio = nullableMoney(cambio);
        this.voucherCode = optionalCode(voucherCode);
        this.referencia = optionalReference(referencia);
        this.cardMode = cardMode;
        this.paymentTerminalProvider = paymentTerminalProvider;
        this.paymentTerminalStatus = paymentTerminalStatus;
        this.cardAuthorizationCode = optionalReference(cardAuthorizationCode);
        this.paymentTerminalId = paymentTerminalId;
        this.creadoEn = Objects.requireNonNull(creadoEn, "creadoEn");
        validateCashAmounts();
        validatePaymentTerminalMetadata();
    }

    public DocumentPayment(
            CommercialDocument documento,
            PaymentMethod metodoPago,
            int posicion,
            BigDecimal importe,
            boolean principal,
            BigDecimal entregado,
            BigDecimal cambio,
            String voucherCode,
            String referencia,
            Instant creadoEn) {
        this(documento, metodoPago, posicion, importe, principal, entregado, cambio,
                voucherCode, referencia, creadoEn, null, null, null, null, null);
    }

    public DocumentPayment(
            CommercialDocument documento,
            PaymentMethod metodoPago,
            int posicion,
            BigDecimal importe,
            boolean principal,
            BigDecimal entregado,
            BigDecimal cambio,
            String voucherCode,
            Instant creadoEn) {
        this(documento, metodoPago, posicion, importe, principal, entregado, cambio, voucherCode, null, creadoEn);
    }

    public DocumentPayment(
            CommercialDocument documento,
            PaymentMethod metodoPago,
            int posicion,
            BigDecimal importe,
            boolean principal,
            BigDecimal entregado,
            BigDecimal cambio,
            Instant creadoEn) {
        this(documento, metodoPago, posicion, importe, principal, entregado, cambio, null, null, creadoEn);
    }

    public CommercialDocument getDocumento() {
        return documento;
    }

    public UUID getId() {
        return id;
    }

    public boolean isPrincipal() {
        return principal;
    }

    public BigDecimal getImporte() {
        return importe;
    }

    public PaymentMethod getMetodoPago() {
        return metodoPago;
    }

    public int getPosicion() {
        return posicion;
    }

    public BigDecimal getEntregado() {
        return entregado;
    }

    public BigDecimal getCambio() {
        return cambio;
    }

    public String getVoucherCode() {
        return voucherCode;
    }

    public String getReferencia() {
        return referencia;
    }

    public PaymentCardMode getCardMode() {
        return cardMode;
    }

    public PaymentTerminalProvider getPaymentTerminalProvider() {
        return paymentTerminalProvider;
    }

    public PaymentTerminalOperationStatus getPaymentTerminalStatus() {
        return paymentTerminalStatus;
    }

    public String getCardAuthorizationCode() {
        return cardAuthorizationCode;
    }

    public UUID getPaymentTerminalId() {
        return paymentTerminalId;
    }

    // Adjusts only the principal payment when a ticket is administratively changed.
    public void adjustAmount(BigDecimal amount) {
        if (!principal) {
            throw new IllegalStateException("solo se puede reajustar el pago principal");
        }
        importe = nonNegative(amount, "importe");
        if (entregado != null && entregado.compareTo(importe) < 0) {
            entregado = importe;
        }
        cambio = entregado == null ? null : Money.euros(entregado.subtract(importe));
    }

    private void validateCashAmounts() {
        if (entregado != null && entregado.compareTo(importe) < 0) {
            throw new IllegalArgumentException("entregado no puede ser menor que importe");
        }
        if (cambio != null && cambio.signum() < 0) {
            throw new IllegalArgumentException("cambio no puede ser negativo");
        }
        if (entregado != null
                && (cambio == null
                || cambio.compareTo(Money.euros(entregado.subtract(importe))) != 0)) {
            throw new IllegalArgumentException(
                    "cambio debe coincidir con entregado menos importe");
        }
        if (entregado == null && cambio != null) {
            throw new IllegalArgumentException(
                    "no puede haber cambio sin importe entregado");
        }
    }

    private static BigDecimal nonNegative(BigDecimal value, String field) {
        var amount = Money.euros(value);
        if (amount.signum() < 0) {
            throw new IllegalArgumentException(field + " no puede ser negativo");
        }
        return amount;
    }

    private static BigDecimal nullableMoney(BigDecimal value) {
        return value == null ? null : Money.euros(value);
    }

    private static String optionalCode(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim().toUpperCase(Locale.ROOT);
    }

    private void validatePaymentTerminalMetadata() {
        if (cardMode == PaymentCardMode.INTEGRATED
                && paymentTerminalStatus != PaymentTerminalOperationStatus.APPROVED) {
            throw new IllegalArgumentException("message.payment_terminal.integrated_payment_not_approved");
        }
        if (cardMode == PaymentCardMode.INTEGRATED
                && (paymentTerminalProvider == null
                || paymentTerminalProvider == PaymentTerminalProvider.NONE
                || paymentTerminalId == null)) {
            throw new IllegalArgumentException("message.payment_terminal.integrated_provider_required");
        }
        if (cardMode == PaymentCardMode.MANUAL
                && paymentTerminalProvider != null
                && paymentTerminalProvider != PaymentTerminalProvider.NONE) {
            throw new IllegalArgumentException("message.payment_terminal.manual_provider_must_be_none");
        }
    }

    private static String optionalReference(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

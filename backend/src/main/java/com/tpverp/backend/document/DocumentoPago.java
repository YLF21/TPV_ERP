package com.tpverp.backend.document;

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
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "documento_pago", uniqueConstraints = @UniqueConstraint(
        columnNames = {"documento_id", "posicion"}))
public class DocumentoPago {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "documento_id", nullable = false)
    private Documento documento;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "metodo_pago_id", nullable = false)
    private MetodoPago metodoPago;
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
    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;
    @Version
    private long version;

    protected DocumentoPago() {
    }

    public DocumentoPago(
            Documento documento,
            MetodoPago metodoPago,
            int posicion,
            BigDecimal importe,
            boolean principal,
            BigDecimal entregado,
            BigDecimal cambio,
            String voucherCode,
            Instant creadoEn) {
        if (posicion < 1) {
            throw new IllegalArgumentException("posición debe ser positiva");
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
        this.creadoEn = Objects.requireNonNull(creadoEn, "creadoEn");
        validateCashAmounts();
    }

    public DocumentoPago(
            Documento documento,
            MetodoPago metodoPago,
            int posicion,
            BigDecimal importe,
            boolean principal,
            BigDecimal entregado,
            BigDecimal cambio,
            Instant creadoEn) {
        this(documento, metodoPago, posicion, importe, principal, entregado, cambio, null, creadoEn);
    }

    public Documento getDocumento() {
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

    public MetodoPago getMetodoPago() {
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

    // Reajusta únicamente el pago principal cuando cambia administrativamente un ticket.
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
}

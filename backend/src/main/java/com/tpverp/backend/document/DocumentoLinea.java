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
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "documento_linea", uniqueConstraints = @UniqueConstraint(
        columnNames = {"documento_id", "posicion"}))
public class DocumentoLinea {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "documento_id", nullable = false)
    private Documento documento;
    @Column(name = "producto_id", nullable = false)
    private UUID productoId;
    @Column(nullable = false)
    private int posicion;
    @Column(nullable = false)
    private int cantidad;
    @Column(nullable = false, length = 128)
    private String codigo;
    @Column(nullable = false)
    private String nombre;
    @Column(length = 16)
    private String tarifa;
    @Column(name = "precio_unitario", nullable = false, precision = 19, scale = 2)
    private BigDecimal precioUnitario;
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal descuento;
    @Column(name = "impuestos_incluidos", nullable = false)
    private boolean impuestosIncluidos;
    @Column(name = "regimen_impuesto", nullable = false, length = 8)
    private String regimenImpuesto;
    @Column(name = "porcentaje_impuesto", nullable = false, precision = 5, scale = 2)
    private BigDecimal porcentajeImpuesto;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal base;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal impuesto;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal total;
    @Version
    private long version;

    protected DocumentoLinea() {
    }

    public DocumentoLinea(
            Documento documento,
            UUID productoId,
            int posicion,
            int cantidad,
            String codigo,
            String nombre,
            String tarifa,
            BigDecimal precioUnitario,
            BigDecimal descuento,
            boolean impuestosIncluidos,
            String regimenImpuesto,
            BigDecimal porcentajeImpuesto) {
        if (cantidad == 0) {
            throw new IllegalArgumentException("cantidad no puede ser cero");
        }
        if (posicion < 1) {
            throw new IllegalArgumentException("posición debe ser positiva");
        }
        this.id = UUID.randomUUID();
        this.documento = Objects.requireNonNull(documento, "documento");
        this.productoId = Objects.requireNonNull(productoId, "productoId");
        this.posicion = posicion;
        this.cantidad = cantidad;
        this.codigo = required(codigo, "codigo");
        this.nombre = required(nombre, "nombre");
        this.tarifa = optional(tarifa);
        this.precioUnitario = nonNegative(precioUnitario, "precioUnitario");
        this.descuento = Money.validPercentage(descuento);
        this.impuestosIncluidos = impuestosIncluidos;
        this.regimenImpuesto = taxRegime(regimenImpuesto);
        this.porcentajeImpuesto = Money.validPercentage(porcentajeImpuesto);
        calculateAmounts();
    }

    public Documento getDocumento() {
        return documento;
    }

    public UUID getProductoId() {
        return productoId;
    }

    public int getPosicion() {
        return posicion;
    }

    public int getCantidad() {
        return cantidad;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getNombre() {
        return nombre;
    }

    public String getTarifa() {
        return tarifa;
    }

    public BigDecimal getPrecioUnitario() {
        return precioUnitario;
    }

    public BigDecimal getDescuento() {
        return descuento;
    }

    public boolean isImpuestosIncluidos() {
        return impuestosIncluidos;
    }

    public String getRegimenImpuesto() {
        return regimenImpuesto;
    }

    public BigDecimal getPorcentajeImpuesto() {
        return porcentajeImpuesto;
    }

    public BigDecimal getBase() {
        return base;
    }

    public BigDecimal getImpuesto() {
        return impuesto;
    }

    public BigDecimal getTotal() {
        return total;
    }

    private void calculateAmounts() {
        var gross = Money.euros(precioUnitario.multiply(BigDecimal.valueOf(cantidad)));
        var discounted = Money.euros(gross.subtract(Money.percentage(gross, descuento)));
        if (impuestosIncluidos) {
            var divisor = BigDecimal.ONE.add(porcentajeImpuesto.divide(HUNDRED));
            base = Money.euros(discounted.divide(divisor, Money.SCALE + 4, Money.ROUNDING));
            impuesto = Money.euros(discounted.subtract(base));
            total = discounted;
            return;
        }
        base = discounted;
        impuesto = Money.percentage(base, porcentajeImpuesto);
        total = Money.euros(base.add(impuesto));
    }

    private static BigDecimal nonNegative(BigDecimal value, String field) {
        var amount = Money.euros(value);
        if (amount.signum() < 0) {
            throw new IllegalArgumentException(field + " no puede ser negativo");
        }
        return amount;
    }

    private static String taxRegime(String value) {
        var regime = required(value, "regimenImpuesto").toUpperCase(java.util.Locale.ROOT);
        if (!regime.equals("IVA") && !regime.equals("IGIC")) {
            throw new IllegalArgumentException("régimen fiscal no válido");
        }
        return regime;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

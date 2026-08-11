package com.tpverp.backend.document;

import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "documento_linea", uniqueConstraints = @UniqueConstraint(
        columnNames = {"documento_id", "posicion"}))
public class DocumentLine {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "documento_id", nullable = false)
    private CommercialDocument documento;
    @Column(name = "producto_id")
    private UUID productoId;
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_linea", nullable = false, length = 24)
    private DocumentLineType lineType = DocumentLineType.PRODUCT;
    @Column(name = "promocion_id")
    private UUID promotionId;
    @Column(name = "promocion_version_id")
    private UUID promotionVersionId;
    @Column(name = "cupon_promocional_id")
    private UUID promotionalCouponId;
    @Column(name = "original_document_line_id")
    private UUID originalDocumentLineId;
    @Column(name = "ticket_regalo_linea_id")
    private UUID giftReceiptLineId;
    @ElementCollection
    @CollectionTable(
            name = "documento_linea_numero_serie",
            joinColumns = @JoinColumn(name = "documento_linea_id"))
    @OrderColumn(name = "posicion")
    @Column(name = "numero_serie", nullable = false, length = 128)
    private List<String> serialNumbers = new ArrayList<>();
    @Column(nullable = false)
    private int posicion;
    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal cantidad;
    @Column(nullable = false, length = 128)
    private String codigo;
    @Column(name = "codigo_barras", length = 128)
    private String codigoBarras;
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
    @Transient
    private Set<Integer> promotionAffectedPositions = Set.of();
    @Version
    private long version;

    protected DocumentLine() {
    }

    public DocumentLine(
            CommercialDocument documento,
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
        this(documento, productoId, posicion, BigDecimal.valueOf(cantidad), codigo, nombre, tarifa,
                precioUnitario, descuento, impuestosIncluidos, regimenImpuesto, porcentajeImpuesto);
    }

    public DocumentLine(
            CommercialDocument documento,
            UUID productoId,
            int posicion,
            BigDecimal cantidad,
            String codigo,
            String nombre,
            String tarifa,
            BigDecimal precioUnitario,
            BigDecimal descuento,
            boolean impuestosIncluidos,
            String regimenImpuesto,
            BigDecimal porcentajeImpuesto) {
        this(documento, productoId, posicion, cantidad, codigo, null, nombre, tarifa,
                precioUnitario, descuento, impuestosIncluidos, regimenImpuesto,
                porcentajeImpuesto);
    }

    public DocumentLine(
            CommercialDocument documento,
            UUID productoId,
            int posicion,
            BigDecimal cantidad,
            String codigo,
            String codigoBarras,
            String nombre,
            String tarifa,
            BigDecimal precioUnitario,
            BigDecimal descuento,
            boolean impuestosIncluidos,
            String regimenImpuesto,
            BigDecimal porcentajeImpuesto) {
        if (quantity(cantidad).signum() == 0) {
            throw new IllegalArgumentException("cantidad no puede ser cero");
        }
        if (posicion < 1) {
            throw new IllegalArgumentException("message.document.position_must_be_positive");
        }
        this.id = UUID.randomUUID();
        this.documento = Objects.requireNonNull(documento, "documento");
        this.productoId = Objects.requireNonNull(productoId, "productoId");
        this.lineType = DocumentLineType.PRODUCT;
        this.posicion = posicion;
        this.cantidad = quantity(cantidad);
        this.codigo = required(codigo, "codigo");
        this.codigoBarras = barcode(codigoBarras);
        this.nombre = required(nombre, "nombre");
        this.tarifa = optional(tarifa);
        this.precioUnitario = nonNegative(precioUnitario, "precioUnitario");
        this.descuento = Money.validPercentage(descuento);
        this.impuestosIncluidos = impuestosIncluidos;
        this.regimenImpuesto = taxRegime(regimenImpuesto);
        this.porcentajeImpuesto = Money.validPercentage(porcentajeImpuesto);
        calculateAmounts();
    }

    private DocumentLine(
            CommercialDocument documento,
            int posicion,
            String description,
            BigDecimal amount,
            boolean impuestosIncluidos,
            String regimenImpuesto,
            BigDecimal porcentajeImpuesto,
            UUID promotionId,
            UUID promotionVersionId,
            UUID promotionalCouponId) {
        this(documento, posicion, description, amount, impuestosIncluidos,
                regimenImpuesto, porcentajeImpuesto, promotionId,
                promotionVersionId, promotionalCouponId, null);
    }

    private DocumentLine(
            CommercialDocument documento,
            int posicion,
            String description,
            BigDecimal amount,
            boolean impuestosIncluidos,
            String regimenImpuesto,
            BigDecimal porcentajeImpuesto,
            UUID promotionId,
            UUID promotionVersionId,
            UUID promotionalCouponId,
            DocumentLineType explicitType) {
        if (posicion < 1) {
            throw new IllegalArgumentException("message.document.position_must_be_positive");
        }
        this.id = UUID.randomUUID();
        this.documento = Objects.requireNonNull(documento, "documento");
        this.productoId = null;
        this.lineType = explicitType != null ? explicitType : promotionalCouponId != null
                ? DocumentLineType.PROMOTIONAL_COUPON
                : promotionId != null ? DocumentLineType.PROMOTION
                : DocumentLineType.MANUAL_DISCOUNT;
        this.promotionId = promotionId;
        this.promotionVersionId = promotionVersionId;
        this.promotionalCouponId = promotionalCouponId;
        this.posicion = posicion;
        this.cantidad = BigDecimal.ONE.setScale(3, Money.ROUNDING);
        this.codigo = required(description, "description");
        this.nombre = required(description, "description");
        this.tarifa = null;
        this.precioUnitario = Money.euros(amount);
        this.descuento = Money.validPercentage(BigDecimal.ZERO);
        this.impuestosIncluidos = impuestosIncluidos;
        this.regimenImpuesto = taxRegime(regimenImpuesto);
        this.porcentajeImpuesto = Money.validPercentage(porcentajeImpuesto);
        calculateAmounts();
    }

    public static DocumentLine promotion(
            CommercialDocument documento,
            int posicion,
            String description,
            BigDecimal amount,
            boolean impuestosIncluidos,
            String regimenImpuesto,
            BigDecimal porcentajeImpuesto,
            UUID promotionId,
            UUID couponId) {
        return new DocumentLine(
                documento, posicion, description, amount, impuestosIncluidos,
                regimenImpuesto, porcentajeImpuesto, promotionId, null, couponId);
    }

    static DocumentLine special(
            CommercialDocument documento,
            int posicion,
            String description,
            BigDecimal amount,
            boolean impuestosIncluidos,
            String regimenImpuesto,
            BigDecimal porcentajeImpuesto,
            UUID promotionId,
            UUID promotionVersionId,
            UUID couponId) {
        return special(
                documento, posicion, description, amount, impuestosIncluidos,
                regimenImpuesto, porcentajeImpuesto, promotionId,
                promotionVersionId, couponId, null);
    }

    static DocumentLine special(
            CommercialDocument documento,
            int posicion,
            String description,
            BigDecimal amount,
            boolean impuestosIncluidos,
            String regimenImpuesto,
            BigDecimal porcentajeImpuesto,
            UUID promotionId,
            UUID promotionVersionId,
            UUID couponId,
            DocumentLineType explicitType) {
        return new DocumentLine(
                documento, posicion, description, amount, impuestosIncluidos,
                regimenImpuesto, porcentajeImpuesto, promotionId, promotionVersionId,
                couponId, explicitType);
    }

    static DocumentLine frozenSpecial(
            CommercialDocument documento,
            int posicion,
            DocumentLineType type,
            String description,
            BigDecimal amount,
            boolean impuestosIncluidos,
            String regimenImpuesto,
            BigDecimal porcentajeImpuesto,
            UUID promotionId,
            UUID promotionVersionId,
            UUID couponId,
            BigDecimal frozenBase,
            BigDecimal frozenTax,
            BigDecimal frozenTotal) {
        if (type == null || type == DocumentLineType.PRODUCT
                || type == DocumentLineType.RETURN_ADJUSTMENT) {
            throw new IllegalArgumentException("tipo de ajuste historico no valido");
        }
        var line = new DocumentLine(
                documento, posicion, description, amount, impuestosIncluidos,
                regimenImpuesto, porcentajeImpuesto, promotionId,
                promotionVersionId, couponId, type);
        line.base = Money.euros(Objects.requireNonNull(frozenBase, "frozenBase"));
        line.impuesto = Money.euros(Objects.requireNonNull(frozenTax, "frozenTax"));
        line.total = Money.euros(Objects.requireNonNull(frozenTotal, "frozenTotal"));
        if (line.base.add(line.impuesto).subtract(line.total).abs()
                .compareTo(new BigDecimal("0.01")) > 0) {
            throw new IllegalArgumentException(
                    "el ajuste historico no cuadra entre base, impuesto y total");
        }
        return line;
    }

    static DocumentLine frozenProduct(
            CommercialDocument documento,
            UUID productoId,
            int posicion,
            BigDecimal cantidad,
            String codigo,
            String nombre,
            String tarifa,
            BigDecimal precioUnitario,
            BigDecimal descuento,
            boolean impuestosIncluidos,
            String regimenImpuesto,
            BigDecimal porcentajeImpuesto,
            BigDecimal frozenBase,
            BigDecimal frozenTax,
            BigDecimal frozenTotal) {
        return frozenProduct(documento, productoId, posicion, cantidad, codigo, null,
                nombre, tarifa, precioUnitario, descuento, impuestosIncluidos,
                regimenImpuesto, porcentajeImpuesto, frozenBase, frozenTax, frozenTotal);
    }

    static DocumentLine frozenProduct(
            CommercialDocument documento,
            UUID productoId,
            int posicion,
            BigDecimal cantidad,
            String codigo,
            String codigoBarras,
            String nombre,
            String tarifa,
            BigDecimal precioUnitario,
            BigDecimal descuento,
            boolean impuestosIncluidos,
            String regimenImpuesto,
            BigDecimal porcentajeImpuesto,
            BigDecimal frozenBase,
            BigDecimal frozenTax,
            BigDecimal frozenTotal) {
        var line = new DocumentLine(
                documento, productoId, posicion, cantidad, codigo, codigoBarras,
                nombre, tarifa, precioUnitario, descuento, impuestosIncluidos,
                regimenImpuesto, porcentajeImpuesto);
        line.base = Money.euros(Objects.requireNonNull(frozenBase, "frozenBase"));
        line.impuesto = Money.euros(Objects.requireNonNull(frozenTax, "frozenTax"));
        line.total = Money.euros(Objects.requireNonNull(frozenTotal, "frozenTotal"));
        if (line.base.add(line.impuesto).subtract(line.total).abs()
                .compareTo(new BigDecimal("0.01")) > 0) {
            throw new IllegalArgumentException(
                    "la linea historica no cuadra entre base, impuesto y total");
        }
        return line;
    }

    static DocumentLine manualDiscount(
            CommercialDocument documento,
            int posicion,
            BigDecimal amount,
            boolean impuestosIncluidos,
            String regimenImpuesto,
            BigDecimal porcentajeImpuesto) {
        return new DocumentLine(
                documento, posicion, "DESCUENTO", amount, impuestosIncluidos,
                regimenImpuesto, porcentajeImpuesto, null, null, null);
    }

    static DocumentLine returnAdjustment(
            CommercialDocument documento,
            int posicion,
            String description,
            BigDecimal amount,
            boolean impuestosIncluidos,
            String regimenImpuesto,
            BigDecimal porcentajeImpuesto) {
        var value = Money.euros(amount);
        return new DocumentLine(
                documento, posicion, description, value, impuestosIncluidos,
                regimenImpuesto, porcentajeImpuesto, null, null, null,
                DocumentLineType.RETURN_ADJUSTMENT);
    }

    public CommercialDocument getDocumento() {
        return documento;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOriginalDocumentLineId() {
        return originalDocumentLineId;
    }

    public void identifyRefundOf(UUID originalLineId) {
        if (originalDocumentLineId != null) throw new IllegalStateException("La linea ya identifica su origen fiscal");
        originalDocumentLineId = Objects.requireNonNull(originalLineId, "originalLineId");
    }

    public UUID getGiftReceiptLineId() {
        return giftReceiptLineId;
    }

    public void identifyGiftReceiptLine(UUID receiptLineId) {
        if (originalDocumentLineId == null) {
            throw new IllegalStateException(
                    "La linea de ticket regalo necesita un origen fiscal");
        }
        if (giftReceiptLineId != null) {
            throw new IllegalStateException(
                    "La linea ya identifica su ticket regalo");
        }
        giftReceiptLineId = Objects.requireNonNull(receiptLineId, "receiptLineId");
    }

    public List<String> getSerialNumbers() {
        return List.copyOf(serialNumbers);
    }

    public void assignSerialNumbers(Collection<String> values) {
        var normalized = normalizeSerialNumbers(values);
        if (!normalized.isEmpty()) {
            var units = cantidad.abs().stripTrailingZeros();
            if (units.scale() > 0 || units.intValueExact() != normalized.size()) {
                throw new IllegalArgumentException(
                        "Cada unidad con numero de serie debe tener un S/N distinto");
            }
        }
        serialNumbers.clear();
        serialNumbers.addAll(normalized);
    }

    public UUID getProductoId() {
        return productoId;
    }

    public DocumentLineType getLineType() {
        return lineType;
    }

    public UUID getPromotionId() {
        return promotionId;
    }

    public UUID getPromotionVersionId() {
        return promotionVersionId;
    }

    public Set<Integer> getPromotionAffectedPositions() {
        return Set.copyOf(promotionAffectedPositions);
    }

    void assignPromotionAffectedPositions(Collection<Integer> positions) {
        if (lineType != DocumentLineType.PROMOTION) {
            throw new IllegalStateException(
                    "Solo una linea de promocion puede identificar productos afectados");
        }
        Objects.requireNonNull(positions, "positions");
        var normalized = positions.stream()
                .map(position -> Objects.requireNonNull(position, "position"))
                .filter(position -> position > 0)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (normalized.size() != positions.size()) {
            throw new IllegalArgumentException(
                    "Las posiciones afectadas por la promocion deben ser positivas y unicas");
        }
        promotionAffectedPositions = normalized;
    }

    public UUID getPromotionalCouponId() {
        return promotionalCouponId;
    }

    public int getPosicion() {
        return posicion;
    }

    public BigDecimal getCantidad() {
        return cantidad;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getCodigoBarras() {
        return codigoBarras;
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
        var gross = Money.euros(precioUnitario.multiply(cantidad));
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

    private static BigDecimal quantity(BigDecimal value) {
        Objects.requireNonNull(value, "cantidad");
        if (value.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException("message.document.quantity_scale");
        }
        return value.setScale(3, Money.ROUNDING);
    }

    private static String taxRegime(String value) {
        var regime = required(value, "regimenImpuesto").toUpperCase(java.util.Locale.ROOT);
        if (!regime.equals("IVA") && !regime.equals("IGIC")) {
            throw new IllegalArgumentException("message.document.invalid_tax_regime");
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

    private static String barcode(String value) {
        var normalized = optional(value);
        if (normalized != null && normalized.length() > 128) {
            throw new IllegalArgumentException(
                    "codigoBarras no puede superar 128 caracteres");
        }
        return normalized;
    }

    private static List<String> normalizeSerialNumbers(Collection<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        var normalized = new ArrayList<String>();
        var unique = new java.util.HashSet<String>();
        for (var value : values) {
            var serial = required(value, "numeroSerie");
            if (serial.length() > 128) {
                throw new IllegalArgumentException("El numero de serie no puede superar 128 caracteres");
            }
            if (!unique.add(serial.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Los numeros de serie de una linea deben ser unicos");
            }
            normalized.add(serial);
        }
        return List.copyOf(normalized);
    }
}

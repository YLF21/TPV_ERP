package com.tpverp.backend.document;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "documento", uniqueConstraints = @UniqueConstraint(
        columnNames = {"tienda_id", "tipo", "numero"}))
public class Documento {

    @Id
    private UUID id;
    @Column(name = "tienda_id", nullable = false)
    private UUID tiendaId;
    @Column(name = "almacen_id", nullable = false)
    private UUID almacenId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private TipoDocumento tipo;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EstadoDocumento estado = EstadoDocumento.BORRADOR;
    @Column(length = 32)
    private String numero;
    @Column(nullable = false)
    private LocalDate fecha;
    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;
    @Column(name = "confirmado_en")
    private Instant confirmadoEn;
    @Column(name = "anulado_en")
    private Instant anuladoEn;
    @Column(name = "creado_por", nullable = false)
    private UUID creadoPor;
    @Column(name = "confirmado_por")
    private UUID confirmadoPor;
    @Column(name = "anulado_por")
    private UUID anuladoPor;
    @Column(name = "cliente_id")
    private UUID clienteId;
    @Column(name = "proveedor_id")
    private UUID proveedorId;
    @Column(name = "numero_externo", length = 128)
    private String numeroExterno;
    @Column(name = "motivo_anulacion")
    private String motivoAnulacion;
    @Column(name = "descuento_global", nullable = false, precision = 5, scale = 2)
    private BigDecimal descuentoGlobal;
    @Column(name = "base_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal baseTotal = Money.euros(BigDecimal.ZERO);
    @Column(name = "impuesto_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal impuestoTotal = Money.euros(BigDecimal.ZERO);
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal total = Money.euros(BigDecimal.ZERO);
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String moneda = "EUR";
    @Column(name = "fecha_vencimiento")
    private LocalDate fechaVencimiento;
    @Column(name = "origen_stock", nullable = false)
    private boolean origenStock;
    @OneToMany(mappedBy = "documento", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("posicion")
    private List<DocumentoLinea> lineas = new ArrayList<>();
    @OneToMany(mappedBy = "documento", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("posicion")
    private List<DocumentoPago> pagos = new ArrayList<>();
    @Version
    private long version;

    protected Documento() {
    }

    public Documento(
            UUID tiendaId,
            UUID almacenId,
            TipoDocumento tipo,
            LocalDate fecha,
            UUID creadoPor,
            BigDecimal descuentoGlobal) {
        this.id = UUID.randomUUID();
        this.tiendaId = Objects.requireNonNull(tiendaId, "tiendaId");
        this.almacenId = Objects.requireNonNull(almacenId, "almacenId");
        this.tipo = Objects.requireNonNull(tipo, "tipo");
        this.fecha = Objects.requireNonNull(fecha, "fecha");
        this.creadoPor = Objects.requireNonNull(creadoPor, "creadoPor");
        this.creadoEn = Instant.now();
        this.descuentoGlobal = Money.validPercentage(descuentoGlobal);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTiendaId() {
        return tiendaId;
    }

    public UUID getAlmacenId() {
        return almacenId;
    }

    public TipoDocumento getTipo() {
        return tipo;
    }

    public EstadoDocumento getEstado() {
        return estado;
    }

    public String getNumero() {
        return numero;
    }

    public LocalDate getFecha() {
        return fecha;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public BigDecimal getBaseTotal() {
        return baseTotal;
    }

    public BigDecimal getImpuestoTotal() {
        return impuestoTotal;
    }

    public UUID getClienteId() {
        return clienteId;
    }

    public UUID getProveedorId() {
        return proveedorId;
    }

    public boolean isOrigenStock() {
        return origenStock;
    }

    public String getMotivoAnulacion() {
        return motivoAnulacion;
    }

    public UUID getStockUserId() {
        if (anuladoPor != null) {
            return anuladoPor;
        }
        return confirmadoPor == null ? creadoPor : confirmadoPor;
    }

    public List<DocumentoLinea> getLineas() {
        return List.copyOf(lineas);
    }

    public List<DocumentoPago> getPagos() {
        return List.copyOf(pagos);
    }

    // Añade una línea perteneciente al mismo documento.
    public void addLine(DocumentoLinea line) {
        if (line == null || line.getDocumento() != this) {
            throw new IllegalArgumentException("la línea no pertenece al documento");
        }
        lineas.add(line);
        recalculate();
    }

    // Añade un pago y protege la unicidad del pago principal antes de persistir.
    public void addPayment(DocumentoPago payment) {
        if (payment == null || payment.getDocumento() != this) {
            throw new IllegalArgumentException("el pago no pertenece al documento");
        }
        if (payment.isPrincipal() && pagos.stream().anyMatch(DocumentoPago::isPrincipal)) {
            throw new IllegalStateException("el documento ya tiene un pago principal");
        }
        pagos.add(payment);
    }

    // Confirma una vez el documento conservando fecha e identidad fiscal.
    public void confirm(String number, UUID userId, Instant confirmedAt, boolean stockApplied) {
        if (estado != EstadoDocumento.BORRADOR || lineas.isEmpty()) {
            throw new IllegalStateException("solo se puede confirmar un borrador con líneas");
        }
        numero = required(number, "numero");
        confirmadoPor = Objects.requireNonNull(userId, "usuario");
        confirmadoEn = Objects.requireNonNull(confirmedAt, "confirmadoEn");
        origenStock = stockApplied;
        estado = isInvoice() ? EstadoDocumento.PENDIENTE : EstadoDocumento.CONFIRMADO;
    }

    // Anula un ticket confirmado sin eliminar su numeración ni contenido.
    public void cancel(UUID userId, Instant cancelledAt, String reason) {
        if (tipo != TipoDocumento.TICKET || estado != EstadoDocumento.CONFIRMADO) {
            throw new IllegalStateException("solo se puede anular un ticket confirmado");
        }
        motivoAnulacion = required(reason, "motivo");
        anuladoPor = Objects.requireNonNull(userId, "usuario");
        anuladoEn = Objects.requireNonNull(cancelledAt, "anuladoEn");
        estado = EstadoDocumento.ANULADO;
    }

    // Registra el pago completo de una factura pendiente.
    public void markPaid() {
        if (!isInvoice() || estado != EstadoDocumento.PENDIENTE) {
            throw new IllegalStateException("solo se puede pagar una factura pendiente");
        }
        estado = EstadoDocumento.PAGADO;
    }

    // Reemplaza contenido editable sin alterar número, fecha ni marca histórica de stock.
    public void adminReplace(
            BigDecimal globalDiscount,
            UUID customerId,
            UUID supplierId,
            List<DocumentLineCommand> newLines) {
        if (estado != EstadoDocumento.CONFIRMADO
                || (tipo != TipoDocumento.TICKET
                && tipo != TipoDocumento.ALBARAN_VENTA
                && tipo != TipoDocumento.ALBARAN_COMPRA)) {
            throw new IllegalStateException("el documento confirmado no admite edición administrativa");
        }
        descuentoGlobal = Money.validPercentage(globalDiscount);
        clienteId = customerId;
        proveedorId = supplierId;
        lineas.clear();
        for (var line : newLines) {
            addLine(line.toEntity(this));
        }
        if (tipo == TipoDocumento.TICKET && !pagos.isEmpty()) {
            var nonPrincipal = pagos.stream()
                    .filter(payment -> !payment.isPrincipal())
                    .map(DocumentoPago::getImporte)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            var principal = pagos.stream().filter(DocumentoPago::isPrincipal).findFirst()
                    .orElseThrow(() -> new IllegalStateException("ticket sin pago principal"));
            principal.adjustAmount(Money.euros(total.subtract(nonPrincipal)));
        }
    }

    void setParties(UUID customerId, UUID supplierId, String externalNumber) {
        clienteId = customerId;
        proveedorId = supplierId;
        numeroExterno = externalNumber == null || externalNumber.isBlank()
                ? null : externalNumber.trim();
    }

    void setDueDate(LocalDate dueDate) {
        fechaVencimiento = dueDate;
    }

    void setStockOrigin(boolean stockOrigin) {
        origenStock = stockOrigin;
    }

    private boolean isInvoice() {
        return tipo == TipoDocumento.FACTURA_VENTA
                || tipo == TipoDocumento.FACTURA_COMPRA
                || tipo == TipoDocumento.RECTIFICATIVA_VENTA
                || tipo == TipoDocumento.RECTIFICATIVA_COMPRA;
    }

    private void recalculate() {
        var lineBase = lineas.stream().map(DocumentoLinea::getBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var lineTax = lineas.stream().map(DocumentoLinea::getImpuesto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var lineTotal = lineas.stream().map(DocumentoLinea::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var globalFactor = BigDecimal.ONE.subtract(descuentoGlobal.movePointLeft(2));
        baseTotal = Money.euros(lineBase.multiply(globalFactor));
        impuestoTotal = Money.euros(lineTax.multiply(globalFactor));
        total = Money.euros(lineTotal.multiply(globalFactor));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}

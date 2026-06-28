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
public class CommercialDocument {

    @Id
    private UUID id;
    @Column(name = "tienda_id", nullable = false)
    private UUID tiendaId;
    @Column(name = "almacen_id", nullable = false)
    private UUID almacenId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private CommercialDocumentType tipo;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DocumentStatus estado = DocumentStatus.BORRADOR;
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
    @Column(name = "num_ticket", length = 32)
    private String numTicket;
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
    private List<DocumentLine> lineas = new ArrayList<>();
    @OneToMany(mappedBy = "documento", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("posicion")
    private List<DocumentPayment> pagos = new ArrayList<>();
    @Version
    private long version;

    protected CommercialDocument() {
    }

    public CommercialDocument(
            UUID tiendaId,
            UUID almacenId,
            CommercialDocumentType tipo,
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

    public CommercialDocumentType getTipo() {
        return tipo;
    }

    public DocumentStatus getEstado() {
        return estado;
    }

    // Returns the fiscal number assigned when the document is confirmed.
    public String getNumero() {
        return numero;
    }

    // Returns the issue date stored by the document.
    public LocalDate getFecha() {
        return fecha;
    }

    // Returns the calculated document total.
    public BigDecimal getTotal() {
        return total;
    }

    public BigDecimal getBaseTotal() {
        return baseTotal;
    }

    public BigDecimal getDescuentoGlobal() {
        return descuentoGlobal;
    }

    // Returns the calculated total tax amount.
    public BigDecimal getImpuestoTotal() {
        return impuestoTotal;
    }

    public String getMoneda() {
        return moneda;
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

    public String getNumTicket() {
        return numTicket;
    }

    public UUID getStockUserId() {
        if (anuladoPor != null) {
            return anuladoPor;
        }
        return confirmadoPor == null ? creadoPor : confirmadoPor;
    }

    public List<DocumentLine> getLineas() {
        return List.copyOf(lineas);
    }

    public List<DocumentPayment> getPagos() {
        return List.copyOf(pagos);
    }

    public BigDecimal getPaidTotal() {
        return Money.euros(pagos.stream()
                .map(DocumentPayment::getImporte)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }
    // Sums actual payments recorded on the document.

    public BigDecimal getPendingTotal() {
        return Money.euros(total.subtract(getPaidTotal()));
    }
    // Calculates the unpaid amount without using cash-session expected totals.

    // Adds a line that belongs to this document.
    public void addLine(DocumentLine line) {
        if (line == null || line.getDocumento() != this) {
            throw new IllegalArgumentException("message.document.line_not_owned");
        }
        lineas.add(line);
        recalculate();
    }

    // Adds a payment and protects principal-payment uniqueness before persistence.
    public void addPayment(DocumentPayment payment) {
        if (payment == null || payment.getDocumento() != this) {
            throw new IllegalArgumentException("el pago no pertenece al documento");
        }
        if (payment.isPrincipal() && pagos.stream().anyMatch(DocumentPayment::isPrincipal)) {
            throw new IllegalStateException("el documento ya tiene un pago principal");
        }
        pagos.add(payment);
    }

    // Confirma una vez el documento conservando fecha e identidad fiscal.
    public void confirm(String number, UUID userId, Instant confirmedAt, boolean stockApplied) {
        if (estado != DocumentStatus.BORRADOR || lineas.isEmpty()) {
            throw new IllegalStateException("message.document.only_draft_with_lines_can_be_confirmed");
        }
        numero = required(number, "numero");
        confirmadoPor = Objects.requireNonNull(userId, "usuario");
        confirmadoEn = Objects.requireNonNull(confirmedAt, "confirmadoEn");
        origenStock = stockApplied;
        estado = isReceivableDocument() ? DocumentStatus.PENDIENTE : DocumentStatus.CONFIRMADO;
    }

    // Cancels a confirmed ticket without removing its number or content.
    public void cancel(UUID userId, Instant cancelledAt, String reason) {
        if (tipo != CommercialDocumentType.TICKET || estado != DocumentStatus.CONFIRMADO) {
            throw new IllegalStateException("solo se puede anular un ticket confirmado");
        }
        motivoAnulacion = required(reason, "motivo");
        anuladoPor = Objects.requireNonNull(userId, "usuario");
        anuladoEn = Objects.requireNonNull(cancelledAt, "anuladoEn");
        estado = DocumentStatus.ANULADO;
    }

    public void updatePaymentStatus() {
        if (!isReceivableDocument()
                || (estado != DocumentStatus.PENDIENTE
                && estado != DocumentStatus.PARCIAL
                && estado != DocumentStatus.PAGADO)) {
            throw new IllegalStateException("message.document.only_receivable_document_can_be_paid");
        }
        var paid = getPaidTotal();
        if (paid.compareTo(total) > 0) {
            throw new IllegalStateException("message.document.payment_exceeds_total");
        }
        estado = paid.signum() == 0
                ? DocumentStatus.PENDIENTE
                : paid.compareTo(total) == 0 ? DocumentStatus.PAGADO : DocumentStatus.PARCIAL;
    }
    // Recalculates PENDIENTE, PARCIAL, or PAGADO from recorded payments.

    // Replaces editable content without changing number, date, or historical stock flag.
    public void adminReplace(
            BigDecimal globalDiscount,
            UUID customerId,
            UUID supplierId,
            List<DocumentLineCommand> newLines) {
        if (!isEditableConfirmedDocument()
                || (tipo != CommercialDocumentType.TICKET
                && tipo != CommercialDocumentType.ALBARAN_VENTA
                && tipo != CommercialDocumentType.ALBARAN_COMPRA)) {
            throw new IllegalStateException("message.document.confirmed_admin_edit_not_allowed");
        }
        descuentoGlobal = Money.validPercentage(globalDiscount);
        clienteId = customerId;
        proveedorId = supplierId;
        lineas.clear();
        for (var line : newLines) {
            addLine(line.toEntity(this));
        }
        if (tipo == CommercialDocumentType.TICKET && !pagos.isEmpty()) {
            var nonPrincipal = pagos.stream()
                    .filter(payment -> !payment.isPrincipal())
                    .map(DocumentPayment::getImporte)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            var principal = pagos.stream().filter(DocumentPayment::isPrincipal).findFirst()
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

    void setNumTicket(String ticketNumber) {
        if (estado != DocumentStatus.BORRADOR) {
            throw new IllegalStateException("num_ticket solo se asigna antes de confirmar");
        }
        numTicket = required(ticketNumber, "num_ticket");
    }

    void setDueDate(LocalDate dueDate) {
        fechaVencimiento = dueDate;
    }

    void setStockOrigin(boolean stockOrigin) {
        origenStock = stockOrigin;
    }

    private boolean isInvoice() {
        return tipo == CommercialDocumentType.FACTURA_VENTA
                || tipo == CommercialDocumentType.FACTURA_COMPRA
                || tipo == CommercialDocumentType.RECTIFICATIVA_VENTA
                || tipo == CommercialDocumentType.RECTIFICATIVA_COMPRA;
    }

    private boolean isReceivableDocument() {
        return isInvoice()
                || tipo == CommercialDocumentType.ALBARAN_VENTA
                || tipo == CommercialDocumentType.ALBARAN_COMPRA;
    }

    private boolean isEditableConfirmedDocument() {
        return estado == DocumentStatus.CONFIRMADO
                || estado == DocumentStatus.PENDIENTE
                || estado == DocumentStatus.PARCIAL
                || estado == DocumentStatus.PAGADO;
    }

    private void recalculate() {
        var lineBase = lineas.stream().map(DocumentLine::getBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var lineTax = lineas.stream().map(DocumentLine::getImpuesto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var lineTotal = lineas.stream().map(DocumentLine::getTotal)
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

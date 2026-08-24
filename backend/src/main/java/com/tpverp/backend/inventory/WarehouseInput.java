package com.tpverp.backend.inventory;

import com.tpverp.backend.document.Money;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "entrada_almacen")
public class WarehouseInput {

    @Id
    private UUID id;

    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Column(name = "almacen_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "proveedor_id")
    private UUID supplierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_documento", nullable = false, length = 32)
    private WarehouseInputDocumentType documentType = WarehouseInputDocumentType.ENTRADA_ALMACEN;

    @Column(name = "numero_externo", length = 128)
    private String externalNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "fuente_precio", nullable = false, length = 16)
    private WarehouseInputPriceSource priceSource = WarehouseInputPriceSource.PURCHASE;

    @Column(name = "descuento_global", nullable = false, precision = 5, scale = 2)
    private BigDecimal globalDiscount = BigDecimal.ZERO;

    @Column(length = 32)
    private String numero;

    @Column(nullable = false)
    private LocalDate fecha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WarehouseInputStatus estado = WarehouseInputStatus.BORRADOR;

    private String origen;

    @Column(columnDefinition = "text")
    private String concepto;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "importacion_excel", columnDefinition = "jsonb")
    private WarehouseExcelImportMetadata excelImport;

    @Column(name = "creada_por", nullable = false)
    private UUID createdBy;

    @Column(name = "confirmada_por")
    private UUID confirmedBy;

    @Column(name = "confirmada_en")
    private Instant confirmedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "entrada_id", insertable = false, updatable = false)
    private List<WarehouseInputLine> lines = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "entrada_almacen_albaran_origen", joinColumns = @JoinColumn(name = "factura_id"))
    @Column(name = "albaran_id", nullable = false)
    private List<UUID> sourceDeliveryNoteIds = new ArrayList<>();

    @Version
    private long version;

    protected WarehouseInput() {
    }

    public WarehouseInput(UUID storeId, UUID warehouseId, LocalDate date, UUID createdBy) {
        this(storeId, warehouseId, date, createdBy, WarehouseInputDocumentType.ENTRADA_ALMACEN);
    }

    public WarehouseInput(
            UUID storeId,
            UUID warehouseId,
            LocalDate date,
            UUID createdBy,
            WarehouseInputDocumentType documentType) {
        this.id = UUID.randomUUID();
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.fecha = Objects.requireNonNull(date, "date");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.documentType = Objects.requireNonNull(documentType, "documentType");
    }

    public UUID getId() {
        return id;
    }

    public String getNumber() {
        return numero;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public WarehouseInputDocumentType getDocumentType() {
        return documentType;
    }

    public String getExternalNumber() {
        return externalNumber;
    }

    public WarehouseInputPriceSource getPriceSource() {
        return priceSource;
    }

    public BigDecimal getGlobalDiscount() {
        return globalDiscount;
    }

    public List<UUID> getSourceDeliveryNoteIds() {
        return List.copyOf(sourceDeliveryNoteIds);
    }

    public LocalDate getDate() {
        return fecha;
    }

    public String getOrigin() {
        return origen;
    }

    public String getConcept() {
        return concepto;
    }

    public WarehouseInputStatus getStatus() {
        return estado;
    }

    public List<WarehouseInputLine> getLines() {
        return List.copyOf(lines);
    }

    public void addLine(UUID productId, int quantity) {
        requireDraft();
        var line = new WarehouseInputLine(id, productId, quantity);
        line.assignPosition(lines.size() + 1);
        lines.add(line);
    }

    public void addLine(WarehouseInputLineCommand line) {
        requireDraft();
        var entity = new WarehouseInputLine(
                id, line.productId(), line.quantity(),
                Objects.requireNonNull(line.unitPrice(), "unitPrice"),
                line.discount(), line.priceOverridden(), line.productName());
        entity.assignPosition(lines.size() + 1);
        lines.add(entity);
    }

    public void replace(
            UUID supplierId,
            String origin,
            String externalNumber,
            String concept,
            WarehouseInputPriceSource priceSource,
            BigDecimal globalDiscount,
            List<UUID> sourceDeliveryNoteIds,
            List<WarehouseInputLineCommand> newLines,
            WarehouseExcelImportMetadata newExcelImport) {
        requireDraft();
        if (newLines == null || newLines.isEmpty()) {
            throw new IllegalArgumentException("message.warehouse_input.at_least_one_line_required");
        }
        this.supplierId = supplierId;
        this.origen = optional(origin);
        this.externalNumber = optional(externalNumber);
        this.concepto = optional(concept);
        this.priceSource = Objects.requireNonNullElse(priceSource, WarehouseInputPriceSource.PURCHASE);
        this.globalDiscount = percent(globalDiscount);
        this.sourceDeliveryNoteIds.clear();
        this.sourceDeliveryNoteIds.addAll(sourceDeliveryNoteIds == null ? List.of() : sourceDeliveryNoteIds.stream().distinct().toList());
        if (newExcelImport != null) {
            this.excelImport = WarehouseExcelImportMetadata.copy(newExcelImport);
        }
        lines.clear();
        newLines.forEach(this::addLine);
    }

    public void replace(
            UUID supplierId,
            String origin,
            String concept,
            List<WarehouseInputLineCommand> newLines,
            WarehouseExcelImportMetadata newExcelImport) {
        replace(supplierId, origin, null, concept, WarehouseInputPriceSource.PURCHASE,
                BigDecimal.ZERO, List.of(), newLines.stream()
                        .map(line -> line.unitPrice() == null ? line.valued(BigDecimal.ZERO) : line)
                        .toList(), newExcelImport);
    }

    public void replace(
            UUID supplierId,
            String origin,
            String concept,
            List<WarehouseInputLineCommand> newLines) {
        replace(supplierId, origin, concept, newLines, null);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public WarehouseExcelImportMetadata getExcelImport() {
        return WarehouseExcelImportMetadata.copy(excelImport);
    }

    public void confirm(String number, UUID userId, Instant when) {
        requireDraft();
        if (lines.isEmpty()) {
            throw new IllegalStateException("No se puede confirmar una entrada sin lineas");
        }
        if (number == null || number.isBlank()) {
            throw new IllegalArgumentException("numero es obligatorio");
        }
        numero = number.trim().toUpperCase(java.util.Locale.ROOT);
        confirmedBy = Objects.requireNonNull(userId, "userId");
        confirmedAt = Objects.requireNonNull(when, "when");
        estado = WarehouseInputStatus.CONFIRMADA;
    }

    public void snapshotPurchasePrices(Map<UUID, BigDecimal> purchasePrices) {
        requireDraft();
        Objects.requireNonNull(purchasePrices, "purchasePrices");
        lines.forEach(line -> line.snapshotPurchaseUnitPrice(Objects.requireNonNull(
                purchasePrices.get(line.getProductId()),
                "Falta el precio de compra del producto " + line.getProductId())));
    }

    public BigDecimal getSubtotal() {
        return Money.euros(lines.stream().map(WarehouseInputLine::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    public BigDecimal getTotal() {
        return Money.euros(getSubtotal().multiply(
                BigDecimal.ONE.subtract(globalDiscount.movePointLeft(2))));
    }

    public boolean createsStockMovement() {
        return documentType != WarehouseInputDocumentType.FACTURA_ENTRADA
                || sourceDeliveryNoteIds.isEmpty();
    }

    private void requireDraft() {
        if (estado != WarehouseInputStatus.BORRADOR) {
            throw new IllegalStateException("Una entrada confirmada es inmutable");
        }
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BigDecimal percent(BigDecimal value) {
        var normalized = Objects.requireNonNullElse(value, BigDecimal.ZERO).setScale(2, Money.ROUNDING);
        if (normalized.signum() < 0 || normalized.compareTo(new BigDecimal("100.00")) > 0) {
            throw new IllegalArgumentException("El descuento debe estar entre 0 y 100");
        }
        return normalized;
    }
}

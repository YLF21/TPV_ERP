package com.tpverp.backend.inventory;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
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
@Table(name = "salida_almacen")
public class WarehouseOutput {

    @Id
    private UUID id;

    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Column(name = "almacen_id", nullable = false)
    private UUID warehouseId;

    @Column(length = 32)
    private String numero;

    @Column(nullable = false)
    private LocalDate fecha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WarehouseOutputStatus estado = WarehouseOutputStatus.BORRADOR;

    private String destino;

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
    @JoinColumn(name = "salida_id", insertable = false, updatable = false)
    private List<WarehouseOutputLine> lines = new ArrayList<>();

    @Version
    private long version;

    protected WarehouseOutput() {
    }

    public WarehouseOutput(UUID storeId, UUID warehouseId, LocalDate date, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.fecha = Objects.requireNonNull(date, "date");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
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

    public LocalDate getDate() {
        return fecha;
    }

    public String getDestination() {
        return destino;
    }

    public String getConcept() {
        return concepto;
    }

    public WarehouseOutputStatus getStatus() {
        return estado;
    }

    public List<WarehouseOutputLine> getLines() {
        return List.copyOf(lines);
    }

    public void addLine(UUID productId, int quantity) {
        requireDraft();
        lines.add(new WarehouseOutputLine(id, productId, quantity));
    }

    // Replaces data and lines while the output remains in draft.
    public void replace(
            String destination,
            String concept,
            List<WarehouseOutputLineCommand> newLines,
            WarehouseExcelImportMetadata newExcelImport) {
        requireDraft();
        if (newLines == null || newLines.isEmpty()) {
            throw new IllegalArgumentException("message.warehouse_output.at_least_one_line_required");
        }
        destino = optional(destination);
        concepto = optional(concept);
        if (newExcelImport != null) {
            excelImport = WarehouseExcelImportMetadata.copy(newExcelImport);
        }
        lines.clear();
        newLines.forEach(line -> addLine(line.productId(), line.quantity()));
    }

    public void replace(
            String destination,
            String concept,
            List<WarehouseOutputLineCommand> newLines) {
        replace(destination, concept, newLines, null);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public WarehouseExcelImportMetadata getExcelImport() {
        return WarehouseExcelImportMetadata.copy(excelImport);
    }

    public void confirm(String number, UUID userId, Instant when) {
        requireDraft();
        if (lines.isEmpty()) {
            throw new IllegalStateException("No se puede confirmar una salida sin lineas");
        }
        if (number == null || number.isBlank()) {
            throw new IllegalArgumentException("numero es obligatorio");
        }
        numero = number.trim().toUpperCase(java.util.Locale.ROOT);
        confirmedBy = Objects.requireNonNull(userId, "userId");
        confirmedAt = Objects.requireNonNull(when, "when");
        estado = WarehouseOutputStatus.CONFIRMADA;
    }

    public void snapshotSalePrices(Map<UUID, BigDecimal> salePrices) {
        requireDraft();
        Objects.requireNonNull(salePrices, "salePrices");
        lines.forEach(line -> line.snapshotSaleUnitPrice(Objects.requireNonNull(
                salePrices.get(line.getProductId()),
                "Falta el precio de venta del producto " + line.getProductId())));
    }

    private void requireDraft() {
        if (estado != WarehouseOutputStatus.BORRADOR) {
            throw new IllegalStateException("Una salida confirmada es inmutable");
        }
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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

    @Column(name = "creada_por", nullable = false)
    private UUID createdBy;

    @Column(name = "confirmada_por")
    private UUID confirmedBy;

    @Column(name = "confirmada_en")
    private Instant confirmedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "entrada_id", insertable = false, updatable = false)
    private List<WarehouseInputLine> lines = new ArrayList<>();

    @Version
    private long version;

    protected WarehouseInput() {
    }

    public WarehouseInput(UUID storeId, UUID warehouseId, LocalDate date, UUID createdBy) {
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

    public UUID getSupplierId() {
        return supplierId;
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
        lines.add(new WarehouseInputLine(id, productId, quantity));
    }

    public void replace(
            UUID supplierId,
            String origin,
            String concept,
            List<WarehouseInputLineCommand> newLines) {
        requireDraft();
        if (newLines == null || newLines.isEmpty()) {
            throw new IllegalArgumentException("message.warehouse_input.at_least_one_line_required");
        }
        this.supplierId = supplierId;
        this.origen = optional(origin);
        this.concepto = optional(concept);
        lines.clear();
        newLines.forEach(line -> addLine(line.productId(), line.quantity()));
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

    private void requireDraft() {
        if (estado != WarehouseInputStatus.BORRADOR) {
            throw new IllegalStateException("Una entrada confirmada es inmutable");
        }
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

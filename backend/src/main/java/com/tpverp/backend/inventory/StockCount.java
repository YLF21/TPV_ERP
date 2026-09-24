package com.tpverp.backend.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "recuento_stock")
public class StockCount {
    @Id private UUID id;
    @Column(name = "numero", nullable = false, length = 40) private String number;
    @Column(name = "tienda_id", nullable = false) private UUID storeId;
    @Column(name = "almacen_id", nullable = false) private UUID warehouseId;
    @Enumerated(EnumType.STRING) @Column(name = "estado", nullable = false, length = 16)
    private StockCountStatus status;
    @Column(name = "notas", columnDefinition = "text") private String notes;
    @Column(name = "fecha", nullable = false) private LocalDate documentDate;
    @Column(name = "revision_edicion", nullable = false) private long editRevision;
    @Column(name = "creado_por", nullable = false) private UUID createdBy;
    @Column(name = "creado_en", nullable = false) private Instant createdAt;
    @Column(name = "confirmado_por") private UUID confirmedBy;
    @Column(name = "confirmado_en") private Instant confirmedAt;
    @Column(name = "cancelado_por") private UUID cancelledBy;
    @Column(name = "cancelado_en") private Instant cancelledAt;
    @Version private long version;

    protected StockCount() {}

    public StockCount(UUID storeId, UUID warehouseId, String notes, UUID createdBy, Instant createdAt) {
        id = UUID.randomUUID();
        this.storeId = Objects.requireNonNull(storeId);
        this.warehouseId = Objects.requireNonNull(warehouseId);
        this.notes = optional(notes);
        this.createdBy = Objects.requireNonNull(createdBy);
        this.createdAt = Objects.requireNonNull(createdAt);
        documentDate = createdAt.atZone(ZoneOffset.UTC).toLocalDate();
        status = StockCountStatus.DRAFT;
    }

    public void number(String value) { number = Objects.requireNonNull(value); }
    public String getNumber() { return number; }

    public void editDraft(LocalDate date, String notes) {
        requireDraft();
        this.documentDate = Objects.requireNonNull(date, "fecha");
        this.notes = optional(notes);
        editRevision++;
    }
    public LocalDate getDocumentDate() { return documentDate; }
    public long getVersion() { return version; }

    public void confirm(UUID userId, Instant when) {
        if (status == StockCountStatus.CONFIRMED) return;
        requireDraft();
        confirmedBy = Objects.requireNonNull(userId);
        confirmedAt = Objects.requireNonNull(when);
        status = StockCountStatus.CONFIRMED;
    }

    public void cancel(UUID userId, Instant when) {
        requireDraft();
        cancelledBy = Objects.requireNonNull(userId);
        cancelledAt = Objects.requireNonNull(when);
        status = StockCountStatus.CANCELLED;
    }

    public void requireDraft() {
        if (status != StockCountStatus.DRAFT) throw new IllegalStateException("El recuento ya no es editable");
    }

    public UUID getId() { return id; }
    public UUID getStoreId() { return storeId; }
    public UUID getWarehouseId() { return warehouseId; }
    public StockCountStatus getStatus() { return status; }
    public String getNotes() { return notes; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getConfirmedBy() { return confirmedBy; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public UUID getCancelledBy() { return cancelledBy; }
    public Instant getCancelledAt() { return cancelledAt; }
    private static String optional(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}

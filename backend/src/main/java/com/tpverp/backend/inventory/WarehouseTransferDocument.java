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
import java.math.BigDecimal;
import com.tpverp.backend.document.Money;
import java.util.UUID;

@Entity
@Table(name = "traspaso_almacen")
public class WarehouseTransferDocument {
    public enum Status { DRAFT, CONFIRMED, CANCELLED }

    @Id private UUID id;
    @Column(name = "tienda_id", nullable = false) private UUID storeId;
    @Column(name = "almacen_origen_id", nullable = false) private UUID sourceWarehouseId;
    @Column(name = "almacen_destino_id", nullable = false) private UUID targetWarehouseId;
    @Column(name = "numero", length = 40) private String number;
    @Enumerated(EnumType.STRING) @Column(name = "estado", nullable = false) private Status status;
    @Column(name = "notas", columnDefinition = "text") private String notes;
    @Column(name = "fecha", nullable = false) private LocalDate date;
    @Column(name = "numero_externo", length = 120) private String externalNumber;
    @Enumerated(EnumType.STRING)
    @Column(name = "origen_precio", nullable = false) private WarehouseInputPriceSource priceSource = WarehouseInputPriceSource.PURCHASE;
    @Column(name = "descuento_global", nullable = false, precision = 5, scale = 2) private BigDecimal globalDiscount = BigDecimal.ZERO;
    @Column(name = "subtotal", nullable = false, precision = 40, scale = 2) private BigDecimal subtotal = BigDecimal.ZERO;
    // Changing only a line must still advance the document's optimistic version.
    @Column(name = "revision_edicion", nullable = false) private long editRevision;
    @Column(name = "creado_por", nullable = false) private UUID createdBy;
    @Column(name = "creado_en", nullable = false) private Instant createdAt;
    @Column(name = "confirmado_por") private UUID confirmedBy;
    @Column(name = "confirmado_en") private Instant confirmedAt;
    @Version private long version;

    protected WarehouseTransferDocument() {}
    public WarehouseTransferDocument(UUID storeId, UUID sourceWarehouseId, UUID targetWarehouseId,
                                     String notes, UUID createdBy, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.storeId = storeId;
        this.sourceWarehouseId = sourceWarehouseId;
        this.targetWarehouseId = targetWarehouseId;
        this.notes = notes;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.date = createdAt.atZone(ZoneOffset.UTC).toLocalDate();
        this.status = Status.DRAFT;
    }
    public void update(UUID sourceWarehouseId, UUID targetWarehouseId, String notes) {
        requireDraft();
        this.sourceWarehouseId = sourceWarehouseId;
        this.targetWarehouseId = targetWarehouseId;
        this.notes = notes;
        this.editRevision++;
    }
    public void valuation(LocalDate date, String externalNumber, WarehouseInputPriceSource priceSource,
                          BigDecimal globalDiscount, BigDecimal subtotal) {
        requireDraft();
        this.date = date == null ? this.date : date;
        this.externalNumber = externalNumber == null || externalNumber.isBlank() ? null : externalNumber.trim();
        if (this.externalNumber != null && this.externalNumber.length() > 120) {
            throw new IllegalArgumentException("El número externo admite hasta 120 caracteres");
        }
        this.priceSource = priceSource == null ? WarehouseInputPriceSource.PURCHASE : priceSource;
        this.globalDiscount = WarehouseTransferLine.percent(globalDiscount);
        this.subtotal = Money.euros(subtotal);
    }
    public void confirm(String number, UUID userId, Instant when) {
        requireDraft();
        this.number = number;
        this.confirmedBy = userId;
        this.confirmedAt = when;
        this.status = Status.CONFIRMED;
    }
    public void cancel() { requireDraft(); status = Status.CANCELLED; }
    public void requireDraft() {
        if (status != Status.DRAFT) throw new IllegalStateException("El traspaso ya no es editable");
    }
    public UUID getId() { return id; }
    public UUID getStoreId() { return storeId; }
    public UUID getSourceWarehouseId() { return sourceWarehouseId; }
    public UUID getTargetWarehouseId() { return targetWarehouseId; }
    public String getNumber() { return number; }
    public Status getStatus() { return status; }
    public String getNotes() { return notes; }
    public LocalDate getDate() { return date; }
    public String getExternalNumber() { return externalNumber; }
    public WarehouseInputPriceSource getPriceSource() { return priceSource; }
    public BigDecimal getGlobalDiscount() { return globalDiscount; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getTotal() { return Money.euros(subtotal.multiply(BigDecimal.ONE.subtract(globalDiscount.movePointLeft(2)))); }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getConfirmedBy() { return confirmedBy; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public long getVersion() { return version; }
}

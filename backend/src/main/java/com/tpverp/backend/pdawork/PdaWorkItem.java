package com.tpverp.backend.pdawork;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "pda_trabajo_operativo")
public class PdaWorkItem {
    @Id private UUID id;
    @Column(name = "tienda_id", nullable = false) private UUID storeId;
    @Enumerated(EnumType.STRING) @Column(name = "tipo", nullable = false, length = 20) private PdaWorkType type;
    @Enumerated(EnumType.STRING) @Column(name = "estado", nullable = false, length = 16) private PdaWorkStatus status;
    @Column(name = "titulo", nullable = false, length = 180) private String title;
    @Column(name = "referencia", length = 120) private String reference;
    @Column(name = "producto_codigo", length = 120) private String productCode;
    @Column(name = "almacen_id") private UUID warehouseId;
    @Column(name = "cantidad", precision = 19, scale = 3) private BigDecimal quantity;
    @Column(name = "numero_lote", length = 120) private String lotNumber;
    @Column(name = "caduca_el") private LocalDate expiryDate;
    @Column(name = "ubicacion", length = 120) private String location;
    @Column(name = "prioridad", length = 16, nullable = false) private String priority;
    @Column(name = "notas", columnDefinition = "text") private String notes;
    @Column(name = "evidencia_nombre", length = 240) private String evidenceName;
    @Column(name = "evidencia_tipo", length = 120) private String evidenceType;
    @Column(name = "evidencia_datos", columnDefinition = "text") private String evidenceData;
    @Column(name = "creado_por", nullable = false) private UUID createdBy;
    @Column(name = "creado_en", nullable = false) private Instant createdAt;
    @Column(name = "completado_por") private UUID completedBy;
    @Column(name = "completado_en") private Instant completedAt;
    @Version private long version;

    protected PdaWorkItem() {}

    public PdaWorkItem(UUID storeId, PdaWorkType type, String title, String reference,
            String productCode, UUID warehouseId, BigDecimal quantity, String lotNumber,
            LocalDate expiryDate, String location, String priority, String notes,
            String evidenceName, String evidenceType, String evidenceData,
            UUID createdBy, Instant createdAt) {
        id = UUID.randomUUID();
        this.storeId = Objects.requireNonNull(storeId);
        this.type = Objects.requireNonNull(type);
        this.title = required(title, "title");
        this.reference = optional(reference);
        this.productCode = optional(productCode);
        this.warehouseId = warehouseId;
        this.quantity = quantity;
        this.lotNumber = optional(lotNumber);
        this.expiryDate = expiryDate;
        this.location = optional(location);
        this.priority = optional(priority) == null ? "NORMAL" : priority.trim().toUpperCase();
        this.notes = optional(notes);
        this.evidenceName = optional(evidenceName);
        this.evidenceType = optional(evidenceType);
        this.evidenceData = optional(evidenceData);
        this.createdBy = Objects.requireNonNull(createdBy);
        this.createdAt = Objects.requireNonNull(createdAt);
        status = PdaWorkStatus.OPEN;
    }

    public void finish(UUID userId, Instant when) { requireOpen(); status=PdaWorkStatus.DONE; completedBy=userId; completedAt=when; }
    public void cancel(UUID userId, Instant when) { requireOpen(); status=PdaWorkStatus.CANCELLED; completedBy=userId; completedAt=when; }
    private void requireOpen() { if(status!=PdaWorkStatus.OPEN) throw new IllegalStateException("La operación ya está cerrada"); }
    private static String optional(String value){return value==null||value.isBlank()?null:value.trim();}
    private static String required(String value,String name){var result=optional(value);if(result==null)throw new IllegalArgumentException(name+" es obligatorio");return result;}

    public UUID getId(){return id;} public UUID getStoreId(){return storeId;} public PdaWorkType getType(){return type;}
    public PdaWorkStatus getStatus(){return status;} public String getTitle(){return title;} public String getReference(){return reference;}
    public String getProductCode(){return productCode;} public UUID getWarehouseId(){return warehouseId;} public BigDecimal getQuantity(){return quantity;}
    public String getLotNumber(){return lotNumber;} public LocalDate getExpiryDate(){return expiryDate;} public String getLocation(){return location;}
    public String getPriority(){return priority;} public String getNotes(){return notes;} public String getEvidenceName(){return evidenceName;}
    public String getEvidenceType(){return evidenceType;} public String getEvidenceData(){return evidenceData;} public UUID getCreatedBy(){return createdBy;}
    public Instant getCreatedAt(){return createdAt;} public UUID getCompletedBy(){return completedBy;} public Instant getCompletedAt(){return completedAt;}
}
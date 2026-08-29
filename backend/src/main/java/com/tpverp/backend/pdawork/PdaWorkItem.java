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
    @Column(name = "asignado_a") private UUID assignedTo;
    @Column(name = "vence_en") private Instant dueAt;
    @Column(name = "ubicacion_origen", length = 120) private String sourceLocation;
    @Column(name = "ubicacion_destino", length = 120) private String destinationLocation;
    @Column(name = "ubicacion_validada_en") private Instant locationValidatedAt;
    @Column(name = "ubicacion_validada_por") private UUID locationValidatedBy;
    @Column(name = "ubicacion_origen_validada_en") private Instant sourceLocationValidatedAt;
    @Column(name = "ubicacion_origen_validada_por") private UUID sourceLocationValidatedBy;
    @Column(name = "ubicacion_destino_validada_en") private Instant destinationLocationValidatedAt;
    @Column(name = "ubicacion_destino_validada_por") private UUID destinationLocationValidatedBy;
    @Column(name = "comprobacion_id") private UUID goodsCheckId;
    @Column(name = "documento_id") private UUID documentId;
    @Column(name = "producto_id") private UUID productId;
    @Column(name = "iniciado_en") private Instant startedAt;
    @Column(name = "iniciado_por") private UUID startedBy;
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

    public void configure(UUID assignedTo, Instant dueAt, String sourceLocation, String destinationLocation,
            UUID goodsCheckId, UUID documentId, UUID productId) {
        requireActive();
        this.assignedTo=assignedTo; this.dueAt=dueAt;
        this.sourceLocation=optional(sourceLocation); this.destinationLocation=optional(destinationLocation);
        this.goodsCheckId=goodsCheckId; this.documentId=documentId; this.productId=productId;
        if (assignedTo!=null && status==PdaWorkStatus.OPEN) status=PdaWorkStatus.PENDING;
    }
    public void assign(UUID userId) { requireActive(); assignedTo=Objects.requireNonNull(userId); if(status==PdaWorkStatus.OPEN)status=PdaWorkStatus.PENDING; }
    public void start(UUID userId, Instant when) {
        requireActive();
        if(assignedTo!=null&&!assignedTo.equals(userId)) throw new PdaWorkConflictException("La operación está asignada a otro usuario");
        assignedTo=userId; status=PdaWorkStatus.IN_PROGRESS; startedBy=userId; startedAt=when;
    }
    public void validateLocation(String scannedCode, PdaLocationRole role, UUID userId, Instant when) {
        requireActive();
        var expected=role==PdaLocationRole.SOURCE?sourceLocation:destinationLocation;
        if(expected==null||!expected.equalsIgnoreCase(required(scannedCode,"locationCode")))
            throw new IllegalArgumentException("La ubicación escaneada no coincide con la operación");
        locationValidatedBy=userId; locationValidatedAt=when;
        if(role==PdaLocationRole.SOURCE){sourceLocationValidatedBy=userId;sourceLocationValidatedAt=when;}
        else{destinationLocationValidatedBy=userId;destinationLocationValidatedAt=when;}
    }
    public void finish(UUID userId, Instant when) {
        requireActive();
        if(sourceLocation!=null&&sourceLocationValidatedAt==null) throw new IllegalStateException("Debe validar la ubicación de origen");
        if(destinationLocation!=null&&destinationLocationValidatedAt==null) throw new IllegalStateException("Debe validar la ubicación de destino");
        status=PdaWorkStatus.DONE; completedBy=userId; completedAt=when;
    }
    public void cancel(UUID userId, Instant when) { requireActive(); status=PdaWorkStatus.CANCELLED; completedBy=userId; completedAt=when; }
    public void requireVersion(Long expectedVersion) {
        if(expectedVersion!=null&&expectedVersion!=version) throw new PdaWorkConflictException("La operación fue modificada por otro dispositivo");
    }
    private void requireActive() { if(status==PdaWorkStatus.DONE||status==PdaWorkStatus.CANCELLED) throw new IllegalStateException("La operación ya está cerrada"); }
    private static String optional(String value){return value==null||value.isBlank()?null:value.trim();}
    private static String required(String value,String name){var result=optional(value);if(result==null)throw new IllegalArgumentException(name+" es obligatorio");return result;}

    public UUID getId(){return id;} public UUID getStoreId(){return storeId;} public PdaWorkType getType(){return type;}
    public PdaWorkStatus getStatus(){return status;} public String getTitle(){return title;} public String getReference(){return reference;}
    public String getProductCode(){return productCode;} public UUID getWarehouseId(){return warehouseId;} public BigDecimal getQuantity(){return quantity;}
    public String getLotNumber(){return lotNumber;} public LocalDate getExpiryDate(){return expiryDate;} public String getLocation(){return location;}
    public String getPriority(){return priority;} public String getNotes(){return notes;} public String getEvidenceName(){return evidenceName;}
    public String getEvidenceType(){return evidenceType;} public String getEvidenceData(){return evidenceData;} public UUID getCreatedBy(){return createdBy;}
    public Instant getCreatedAt(){return createdAt;} public UUID getCompletedBy(){return completedBy;} public Instant getCompletedAt(){return completedAt;}
    public UUID getAssignedTo(){return assignedTo;} public Instant getDueAt(){return dueAt;} public String getSourceLocation(){return sourceLocation;}
    public String getDestinationLocation(){return destinationLocation;} public Instant getLocationValidatedAt(){return locationValidatedAt;}
    public UUID getLocationValidatedBy(){return locationValidatedBy;} public UUID getGoodsCheckId(){return goodsCheckId;} public UUID getDocumentId(){return documentId;}
    public UUID getProductId(){return productId;} public Instant getStartedAt(){return startedAt;} public UUID getStartedBy(){return startedBy;} public long getVersion(){return version;}
    public Instant getSourceLocationValidatedAt(){return sourceLocationValidatedAt;} public Instant getDestinationLocationValidatedAt(){return destinationLocationValidatedAt;}
}
package com.tpverp.backend.pdawork;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PdaWorkView(UUID id, PdaWorkType type, PdaWorkStatus status, String title,
        String reference, String productCode, UUID warehouseId, BigDecimal quantity,
        String lotNumber, LocalDate expiryDate, String location, String priority, String notes,
        String evidenceName, String evidenceType, String evidenceData,
        Instant createdAt, Instant completedAt, UUID assignedTo, Instant dueAt,
        String sourceLocation, String destinationLocation, Instant locationValidatedAt,
        UUID goodsCheckId, UUID documentId, UUID productId, Instant startedAt,
        Instant sourceLocationValidatedAt, Instant destinationLocationValidatedAt,
        long version, List<PdaWorkEvidenceView> evidences) {
    static PdaWorkView from(PdaWorkItem value) { return from(value,List.of()); }
    static PdaWorkView from(PdaWorkItem value,List<PdaWorkEvidenceView> evidences) {
        return new PdaWorkView(value.getId(), value.getType(), value.getStatus(), value.getTitle(),
                value.getReference(), value.getProductCode(), value.getWarehouseId(), value.getQuantity(),
                value.getLotNumber(), value.getExpiryDate(), value.getLocation(), value.getPriority(), value.getNotes(),
                value.getEvidenceName(), value.getEvidenceType(), null, value.getCreatedAt(), value.getCompletedAt(),
                value.getAssignedTo(),value.getDueAt(),value.getSourceLocation(),value.getDestinationLocation(),value.getLocationValidatedAt(),
                value.getGoodsCheckId(),value.getDocumentId(),value.getProductId(),value.getStartedAt(),
                value.getSourceLocationValidatedAt(),value.getDestinationLocationValidatedAt(),value.getVersion(),evidences);
    }
}
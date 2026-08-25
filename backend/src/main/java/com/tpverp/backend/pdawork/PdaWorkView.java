package com.tpverp.backend.pdawork;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PdaWorkView(UUID id, PdaWorkType type, PdaWorkStatus status, String title,
        String reference, String productCode, UUID warehouseId, BigDecimal quantity,
        String lotNumber, LocalDate expiryDate, String location, String priority, String notes,
        String evidenceName, String evidenceType, String evidenceData,
        Instant createdAt, Instant completedAt) {
    static PdaWorkView from(PdaWorkItem value) {
        return new PdaWorkView(value.getId(), value.getType(), value.getStatus(), value.getTitle(),
                value.getReference(), value.getProductCode(), value.getWarehouseId(), value.getQuantity(),
                value.getLotNumber(), value.getExpiryDate(), value.getLocation(), value.getPriority(), value.getNotes(),
                value.getEvidenceName(), value.getEvidenceType(), value.getEvidenceData(), value.getCreatedAt(), value.getCompletedAt());
    }
}
package com.tpverp.backend.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProductBulkEditView(
        UUID id,
        String code,
        UUID seriesId,
        int versionNumber,
        UUID previousVersionId,
        String name,
        ProductBulkEditStatus status,
        List<ProductBulkEditContent.Row> content,
        long version,
        UUID createdById,
        String createdBy,
        Instant createdAt,
        UUID updatedById,
        String updatedBy,
        Instant updatedAt,
        UUID appliedById,
        String appliedBy,
        Instant appliedAt,
        List<Comment> comments) {

    public record Comment(
            UUID id,
            UUID userId,
            String username,
            String text,
            Instant createdAt) {
    }
}

package com.tpverp.backend.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProductPriceRuleView(
        UUID id,
        String name,
        List<ProductPriceRuleForm.Definition> forms,
        UUID createdById,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

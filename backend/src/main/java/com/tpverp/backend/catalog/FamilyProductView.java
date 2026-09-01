package com.tpverp.backend.catalog;

import java.math.BigDecimal;
import java.util.UUID;

/** Lightweight row returned by the family/subfamily product list. */
public record FamilyProductView(
        UUID id,
        long version,
        String imageId,
        String imageHash,
        String code,
        String barcode,
        String name,
        BigDecimal salePrice,
        UUID familyId,
        UUID subfamilyId,
        boolean active) {
}

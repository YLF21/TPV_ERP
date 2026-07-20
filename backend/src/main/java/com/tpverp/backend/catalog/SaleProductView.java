package com.tpverp.backend.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record SaleProductView(
        UUID id,
        boolean active,
        String code,
        String barcode,
        String barcode2,
        String name,
        BigDecimal salePrice,
        BigDecimal memberPrice,
        BigDecimal offerPrice,
        BigDecimal offerDiscountPercent,
        PriceUseMode priceUseMode,
        DiscountType discountType,
        boolean offerActive,
        LocalDate offerFrom,
        LocalDate offerUntil,
        boolean taxesIncluded,
        UUID taxId,
        BigDecimal taxPercentage,
        String taxRegime) {
}

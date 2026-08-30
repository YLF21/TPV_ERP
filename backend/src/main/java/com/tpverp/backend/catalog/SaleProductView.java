package com.tpverp.backend.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record SaleProductView(
        UUID id,
        UUID imageId,
        boolean active,
        ProductType productType,
        boolean requiresSerialNumber,
        String code,
        String barcode,
        String barcode2,
        String name,
        BigDecimal salePrice,
        BigDecimal memberPrice,
        BigDecimal wholesalePrice,
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
        String taxRegime,
        BigDecimal packageQuantity,
        BigDecimal totalStock) {
}

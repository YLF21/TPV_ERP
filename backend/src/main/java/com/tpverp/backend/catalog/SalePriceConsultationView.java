package com.tpverp.backend.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record SalePriceConsultationView(
        UUID productId,
        String code,
        String name,
        BigDecimal salePrice,
        PriceUseMode activePriceType,
        BigDecimal memberPrice,
        BigDecimal offerPrice,
        BigDecimal offerDiscountPercent,
        LocalDate offerUntil) {
}

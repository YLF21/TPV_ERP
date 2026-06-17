package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ParkedSaleView(
        UUID id,
        Instant createdAt,
        UUID createdBy,
        UUID customerId,
        String comment,
        BigDecimal total) {

    public static ParkedSaleView from(ParkedSale sale) {
        return new ParkedSaleView(
                sale.getId(), sale.getCreatedAt(), sale.getCreatedBy(),
                sale.getCustomerId(), sale.getComment(), sale.getTotal());
    }
}

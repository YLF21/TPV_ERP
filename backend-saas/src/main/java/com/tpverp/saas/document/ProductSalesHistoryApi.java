package com.tpverp.saas.document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read-only installation API. Decimal strings preserve the received historical precision. */
public final class ProductSalesHistoryApi {
    private ProductSalesHistoryApi() { }
    public record Request(UUID companyId, UUID storeId, String productCode, LocalDate from, LocalDate to,
            String status, List<UUID> storeIds, String sortBy, String sortDirection, Integer size, String cursor, String view) { }
    public record Response(UUID companyId, String productCode, String coverage, List<Item> items,
            List<Store> stores, List<Total> totals, List<Comparison> comparison, String nextCursor,
            boolean hasMore, long incompleteDocuments, Instant receivedAt) { }
    public record Item(UUID documentId, String documentType, String documentNumber, String status,
            LocalDate businessDate, Instant occurredAt, UUID storeId, String storeCode, String storeName,
            UUID installationId, int linePosition, String productCode, String productName, String quantity,
            String unitPrice, String discountPercent, String lineTotal, String currency, String customerName,
            String userName, String warehouseName, boolean countsAsSale) { }
    public record Store(UUID id, String code, String name) { }
    public record Total(String currency, String quantitySold, String quantityReturned, String netQuantity,
            String netAmount) { }
    public record Comparison(UUID storeId, String storeCode, String storeName, String currency,
            String quantitySold, String quantityReturned, String netQuantity, String netAmount) { }
}

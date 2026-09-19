package com.tpverp.saas.document;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class ProductSalesHistoryQuery {
    private ProductSalesHistoryQuery() { }
    record Filter(String productCode, LocalDate from, LocalDate to, String status, Set<UUID> storeIds) {
        Filter {
            if (productCode == null || productCode.isBlank() || productCode.length() > 1024
                    || productCode.indexOf('\0') >= 0) throw invalid("productCode");
            for (LocalDate date : new LocalDate[] {from, to}) {
                if (date != null && (date.getYear() < 1 || date.getYear() > 9999)) throw invalid("date");
            }
            if (from != null && to != null && from.isAfter(to)) throw invalid("date range");
            if (status != null) {
                try { CommercialDocumentQuery.Status.valueOf(status); }
                catch (IllegalArgumentException exception) { throw invalid("status"); }
            }
            storeIds = storeIds == null ? Set.of() : Set.copyOf(storeIds);
            if (storeIds.size() > 2000) throw invalid("storeIds");
        }
    }
    enum Field {
        OCCURRED_AT("occurredAt", "occurred_at", Kind.INSTANT), DOCUMENT("document", "document_number", Kind.TEXT),
        STATUS("status", "document_status", Kind.TEXT), CUSTOMER("customer", "customer_name", Kind.TEXT),
        QUANTITY("quantity", "quantity", Kind.DECIMAL), UNIT_PRICE("unitPrice", "unit_price", Kind.DECIMAL),
        DISCOUNT("discount", "discount_percent", Kind.DECIMAL), TOTAL("total", "line_total", Kind.DECIMAL),
        USER("user", "user_name", Kind.TEXT), STORE("store", "store_code", Kind.TEXT),
        WAREHOUSE("warehouse", "warehouse_name", Kind.TEXT), CURRENCY("currency", "currency", Kind.TEXT);
        final String key;
        final String column;
        final Kind kind;
        Field(String key, String column, Kind kind) { this.key = key; this.column = column; this.kind = kind; }
        String sql() { return kind == Kind.TEXT ? "coalesce(" + column + ", '') collate \"C\"" : column; }
    }
    enum Kind { INSTANT, DECIMAL, TEXT }
    record Order(Field field, boolean ascending) {
        static Order parse(String key, String direction) {
            Field field = Field.OCCURRED_AT;
            if (key != null) {
                field = null;
                for (Field candidate : Field.values()) if (candidate.key.equals(key)) field = candidate;
                if (field == null) throw invalid("sortBy");
            }
            String normalized = direction == null ? "desc" : direction.toLowerCase(Locale.ROOT);
            if (!normalized.equals("asc") && !normalized.equals("desc")) throw invalid("sortDirection");
            return new Order(field, normalized.equals("asc"));
        }
    }
    static ResponseStatusException invalid(String field) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid product sales history " + field);
    }
}

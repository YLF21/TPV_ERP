package com.tpverp.saas.document;

import static com.tpverp.saas.document.ProductSalesHistoryQuery.*;

import com.tpverp.saas.document.CommercialDocumentQuery.Scope;
import com.tpverp.saas.document.ProductSalesHistoryApi.Item;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** Keyset boundary, bound to trusted scope and every filter; never an authorization credential. */
record ProductSalesHistoryCursor(String fingerprint, String value, UUID storeId, UUID documentId, int position) {
    static String fingerprint(Scope scope, Filter filter, Order order) {
        String canonical = String.join("|", "1", scope.companyId().toString(), "" + scope.companyWide(),
                scope.allowedStoreIds().stream().map(UUID::toString).sorted().toList().toString(),
                encodePart(filter.productCode()), "" + filter.from(), "" + filter.to(), "" + filter.status(),
                filter.storeIds().stream().map(UUID::toString).sorted().toList().toString(),
                order.field().name(), "" + order.ascending());
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    static ProductSalesHistoryCursor from(Item row, String fingerprint, Order order) {
        String value = switch (order.field()) {
            case OCCURRED_AT -> row.occurredAt().toString(); case DOCUMENT -> row.documentNumber();
            case STATUS -> row.status(); case CUSTOMER -> row.customerName(); case QUANTITY -> row.quantity();
            case UNIT_PRICE -> row.unitPrice(); case DISCOUNT -> row.discountPercent(); case TOTAL -> row.lineTotal();
            case USER -> row.userName(); case STORE -> row.storeCode(); case WAREHOUSE -> row.warehouseName();
            case CURRENCY -> row.currency();
        };
        return new ProductSalesHistoryCursor(fingerprint, value == null ? "" : value, row.storeId(), row.documentId(), row.linePosition());
    }
    static ProductSalesHistoryCursor decode(String raw, String fingerprint, Order order) {
        if (raw == null) return null;
        try {
            if (raw.length() > 4096) throw invalid("cursor");
            String[] fields = decodePart(raw).split("\\|", -1);
            if (fields.length != 6 || !fields[0].equals("1") || !fields[1].equals(fingerprint)) throw invalid("cursor");
            var cursor = new ProductSalesHistoryCursor(fingerprint, decodePart(fields[2]), uuid(fields[3]),
                    uuid(fields[4]), Integer.parseInt(fields[5]));
            if (cursor.position() < 1) throw invalid("cursor");
            cursor.jdbcValue(order);
            return cursor;
        } catch (RuntimeException exception) { throw invalid("cursor"); }
    }
    Object jdbcValue(Order order) {
        if (value.length() > 1024 || value.indexOf('\0') >= 0) throw invalid("cursor");
        return switch (order.field().kind) {
            case TEXT -> value;
            case DECIMAL -> {
                if (!value.matches("-?[0-9]+(?:\\.[0-9]+)?")) throw invalid("cursor");
                yield new BigDecimal(value);
            }
            case INSTANT -> java.sql.Timestamp.from(Instant.parse(value));
        };
    }
    String encode() { return encodePart("1|" + fingerprint + "|" + encodePart(value) + "|" + storeId + "|" + documentId + "|" + position); }
    private static String encodePart(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String decodePart(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
    private static UUID uuid(String value) {
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid("cursor");
        return UUID.fromString(value);
    }
}

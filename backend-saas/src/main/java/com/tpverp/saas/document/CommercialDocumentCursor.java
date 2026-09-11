package com.tpverp.saas.document;

import static com.tpverp.saas.document.CommercialDocumentQuery.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.UUID;

/** Bounded keyset cursor bound to filters, order AND authorized scope; not an authorization credential. */
record CommercialDocumentCursor(String fingerprint, String sortValue, UUID storeId, UUID documentId) {
    static CommercialDocumentCursor from(Row row, String fingerprint, Order order) {
        String value = switch (order.field()) {
            case DATE -> row.date().toString();
            case NUMBER -> row.number();
            case TYPE -> row.type().name();
            case STATUS -> row.status().name();
            case BASE -> row.subtotal().toPlainString();
            case TAX -> row.taxTotal().toPlainString();
            case TOTAL -> row.total().toPlainString();
            case TERMINAL -> row.terminalName() == null ? "" : row.terminalName();
            case USER -> row.userName() == null ? "" : row.userName();
            case STORE -> row.storeCode();
            case CURRENCY -> row.currency();
        };
        return new CommercialDocumentCursor(fingerprint, value, row.storeId(), row.documentId());
    }

    static CommercialDocumentCursor decode(String raw, String fingerprint, Order order) {
        if (raw == null || raw.isBlank()) return null;
        try {
            if (raw.length() > 2048) throw invalid("cursor");
            String[] parts = decodePart(raw).split("\\|", -1);
            if (parts.length != 5 || !parts[0].equals("1") || !parts[1].equals(fingerprint)) throw invalid("cursor");
            var cursor = new CommercialDocumentCursor(parts[1], parts[2].equals("~") ? null : decodePart(parts[2]), uuid(parts[3]), uuid(parts[4]));
            cursor.jdbcValue(order); // Validate the typed boundary before SQL is executed.
            return cursor;
        } catch (RuntimeException exception) {
            throw invalid("cursor");
        }
    }

    Object jdbcValue(Order order) {
        if (sortValue != null && sortValue.isEmpty()
                && (order.field() == SortField.USER || order.field() == SortField.TERMINAL)) return "";
        if (sortValue == null || sortValue.isEmpty() || sortValue.length() > 255
                || sortValue.codePoints().anyMatch(Character::isISOControl)) throw invalid("cursor");
        return switch (order.field()) {
            case DATE -> {
                if (!sortValue.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid("cursor");
                LocalDate value = LocalDate.parse(sortValue);
                requireDate(value);
                yield java.sql.Date.valueOf(value);
            }
            case NUMBER -> {
                if (sortValue.length() > 32 || !sortValue.equals(sortValue.strip())) throw invalid("cursor");
                yield sortValue;
            }
            case TYPE -> Type.valueOf(sortValue).name();
            case STATUS -> Status.valueOf(sortValue).name();
            case USER, TERMINAL -> {
                if (!sortValue.equals(sortValue.strip())) throw invalid("cursor");
                yield sortValue;
            }
            case STORE -> {
                if (sortValue.length() > 64) throw invalid("cursor");
                yield sortValue;
            }
            case CURRENCY -> {
                if (!sortValue.matches("[A-Z]{3}")) throw invalid("cursor");
                yield sortValue;
            }
            case BASE, TAX, TOTAL -> {
                if (sortValue.length() > 64) throw invalid("cursor");
                if (!sortValue.matches("-?[0-9]+(?:\\.[0-9]+)?")) throw invalid("cursor");
                BigDecimal value = new BigDecimal(sortValue).setScale(2, RoundingMode.UNNECESSARY);
                if (value.precision() > 19) throw invalid("cursor");
                yield value;
            }
        };
    }

    String encode() {
        return encodePart("1|" + fingerprint + "|" + (sortValue == null ? "~" : encodePart(sortValue)) + "|" + storeId + "|" + documentId);
    }

    static String fingerprint(Scope scope, Filter filter, Order order) {
        String canonical = String.join("|", "1", scope.companyId().toString(), Boolean.toString(scope.companyWide()),
                sorted(scope.allowedStoreIds()), sorted(filter.storeIds()), text(filter.customerId()),
                sorted(filter.types()), sorted(filter.statuses()), text(filter.from()), text(filter.to()),
                filter.actor() == null ? "~" : filter.actor().role() + ":" + filter.actor().installationId() + ":" + filter.actor().localUserId(),
                filter.numberContains() == null ? "~" : encodePart(filter.numberContains()), order.field().name(), order.direction().name());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String sorted(Collection<?> values) { return values.stream().map(Object::toString).sorted().toList().toString(); }
    private static String text(Object value) { return value == null ? "~" : value.toString(); }
    private static String encodePart(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String decodePart(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
    private static UUID uuid(String value) {
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid("cursor");
        return UUID.fromString(value);
    }
}

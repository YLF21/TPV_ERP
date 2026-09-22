package com.tpverp.saas.supervision;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Versioned allowlist: no arbitrary error messages, personal data or secrets. */
public record StoreFailureSnapshot(UUID installationId, String source, UUID sourceId,
        long sourceRevision, String status, String severity, String code,
        Instant firstSeenAt, Instant lastSeenAt, long occurrences) {
    private static final Set<String> FIELDS = Set.of("schemaVersion", "installationId", "source",
            "sourceId", "sourceRevision", "status", "severity", "code", "firstSeenAt", "lastSeenAt", "occurrences");
    private static final Set<String> CONTROL_CODES = Set.of("SALE_SCREEN_CLEARED", "CONSECUTIVE_LINE_DELETIONS",
            "MANUAL_PRICE_CHANGE_OVER_PERCENT", "MANUAL_PRICE_CHANGED", "MANUAL_DISCOUNT_OVER_PERCENT",
            "PRODUCT_DISCOUNT_APPLIED", "TICKET_CANCELLED", "INACTIVE_PRODUCT_SOLD", "MANUAL_NEGATIVE_QUANTITY",
            "REFUND_POLICY_OVERRIDE", "CASH_DRAWER_OPENED", "CASH_SESSION_DISCREPANCY",
            "PRODUCT_CATALOG_MODIFIED", "PARKED_SALE_DELETED");

    public static StoreFailureSnapshot parse(Map<String, Object> payload, Instant now) {
        try {
            if (!FIELDS.equals(payload.keySet()) || integer(payload, "schemaVersion") != 1) throw invalid();
            String source = text(payload, "source");
            String code = text(payload, "code");
            String status = text(payload, "status");
            String severity = text(payload, "severity");
            boolean control = "LOCAL_CONTROL".equals(source);
            if (!(control ? CONTROL_CODES.contains(code)
                    : "LOCAL_SYNC".equals(source) && "SYNC_DELIVERY_FAILED".equals(code))) throw invalid();
            if (!(control ? Set.of("OPEN", "REVIEWED", "RESOLVED", "DISMISSED") : Set.of("OPEN", "RESOLVED")).contains(status)) throw invalid();
            if (!Set.of("INFO", "WARNING", "DANGER").contains(severity)) throw invalid();
            Instant first = Instant.parse(text(payload, "firstSeenAt"));
            Instant last = Instant.parse(text(payload, "lastSeenAt"));
            long revision = integer(payload, "sourceRevision");
            long occurrences = integer(payload, "occurrences");
            if (revision < 0 || occurrences < 1 || first.isAfter(last) || last.isAfter(now.plusSeconds(300))) throw invalid();
            return new StoreFailureSnapshot(UUID.fromString(text(payload, "installationId")), source,
                    UUID.fromString(text(payload, "sourceId")), revision, status, severity, code, first, last, occurrences);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static long integer(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof Number)) throw invalid();
        return new BigDecimal(value.toString()).longValueExact();
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw invalid();
        return text;
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe operativo invalido");
    }
}

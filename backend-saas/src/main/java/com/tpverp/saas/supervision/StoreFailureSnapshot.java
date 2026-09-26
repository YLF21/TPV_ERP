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
        Instant firstSeenAt, Instant lastSeenAt, long occurrences, String module, String appVersion,
        String traceId, String exceptionType, String errorLocation) {
    private static final Set<String> FIELDS = Set.of("schemaVersion", "installationId", "source",
            "sourceId", "sourceRevision", "status", "severity", "code", "firstSeenAt", "lastSeenAt", "occurrences");
    private static final Set<String> V2_FIELDS = java.util.stream.Stream.concat(FIELDS.stream(),
            Set.of("module", "appVersion", "traceId", "exceptionType", "errorLocation").stream())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final String JAVA_NAME = "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*";
    private static final Set<String> CONTROL_CODES = Set.of("SALE_SCREEN_CLEARED", "CONSECUTIVE_LINE_DELETIONS",
            "MANUAL_PRICE_CHANGE_OVER_PERCENT", "MANUAL_PRICE_CHANGED", "MANUAL_DISCOUNT_OVER_PERCENT",
            "PRODUCT_DISCOUNT_APPLIED", "TICKET_CANCELLED", "INACTIVE_PRODUCT_SOLD", "MANUAL_NEGATIVE_QUANTITY",
            "REFUND_POLICY_OVERRIDE", "CASH_DRAWER_OPENED", "CASH_SESSION_DISCREPANCY",
            "PRODUCT_CATALOG_MODIFIED", "PARKED_SALE_DELETED");

    public static StoreFailureSnapshot parse(Map<String, Object> payload, Instant now) {
        try {
            long version = integer(payload, "schemaVersion");
            if (!(version == 1 && FIELDS.equals(payload.keySet())
                    || version == 2 && V2_FIELDS.equals(payload.keySet()))) throw invalid();
            String source = text(payload, "source");
            String code = text(payload, "code");
            String status = text(payload, "status");
            String severity = text(payload, "severity");
            boolean control = "LOCAL_CONTROL".equals(source);
            boolean application = version == 2 && "LOCAL_APPLICATION".equals(source);
            if (!(control ? CONTROL_CODES.contains(code)
                    : application ? "APPLICATION_ERROR".equals(code)
                    : "LOCAL_SYNC".equals(source) && "SYNC_DELIVERY_FAILED".equals(code))) throw invalid();
            if (!(control ? Set.of("OPEN", "REVIEWED", "RESOLVED", "DISMISSED") : Set.of("OPEN", "RESOLVED")).contains(status)) throw invalid();
            if (!Set.of("INFO", "WARNING", "DANGER").contains(severity)) throw invalid();
            // An application exception alone provides no evidence of recovery.
            if (application && !("OPEN".equals(status) && "DANGER".equals(severity))) throw invalid();
            String module = optional(payload, "module", 16, "SALES|PRINTING|SYNC|APPLICATION");
            String appVersion = optional(payload, "appVersion", 80, "[A-Za-z0-9][A-Za-z0-9._+\\-]*");
            String trace = optional(payload, "traceId", 128,
                    "[A-Za-z0-9._-]{8,128}");
            String exceptionType = optional(payload, "exceptionType", 160, JAVA_NAME);
            String errorLocation = optional(payload, "errorLocation", 240,
                    JAVA_NAME + "\\.[A-Za-z_$][A-Za-z0-9_$]*:[0-9]{1,9}");
            Instant first = Instant.parse(text(payload, "firstSeenAt"));
            Instant last = Instant.parse(text(payload, "lastSeenAt"));
            long revision = integer(payload, "sourceRevision");
            long occurrences = integer(payload, "occurrences");
            if (revision < 0 || occurrences < 1 || first.isBefore(Instant.EPOCH) || first.isAfter(last) || last.isAfter(now.plusSeconds(300))) throw invalid();
            return new StoreFailureSnapshot(UUID.fromString(text(payload, "installationId")), source,
                    UUID.fromString(text(payload, "sourceId")), revision, status, severity, code, first, last, occurrences,
                    module, appVersion, trace, exceptionType, errorLocation);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static String optional(Map<String, Object> data, String key, int limit, String grammar) {
        Object value = data.get(key);
        if (value == null) return null;
        if (!(value instanceof String text) || text.length() > limit || !text.matches(grammar)) throw invalid();
        return text;
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

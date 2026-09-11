package com.tpverp.saas.document;

import com.tpverp.saas.sync.SyncEventRequest;
import com.tpverp.saas.sync.SyncOperation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Header only: amounts and local identities are copied, never recalculated or resolved to central IDs. */
public record CommercialDocumentSnapshot(
        long sourceRevision,
        String type,
        String status,
        String number,
        LocalDate businessDate,
        String currency,
        BigDecimal subtotal,
        BigDecimal taxTotal,
        BigDecimal total,
        UUID customerLocalId,
        UUID createdByLocalId,
        UUID confirmedByLocalId,
        Instant sourceCreatedAt,
        Instant sourceConfirmedAt,
        UUID originTerminalLocalId) {

    public static final int SCHEMA_VERSION = 2;
    private static final Set<String> TYPES = Set.of(
            "TICKET", "ALBARAN_VENTA", "FACTURA_VENTA", "RECTIFICATIVA_VENTA");
    private static final Set<String> STATUSES = Set.of(
            "CONFIRMADO", "ANULADO", "PENDIENTE", "PARCIAL", "PAGADO");
    private static final Set<SyncOperation> OPERATIONS = Set.of(
            SyncOperation.CONFIRMAR, SyncOperation.ACTUALIZAR, SyncOperation.ANULAR);
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DATE_TEXT = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern MONEY_TEXT = Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?");

    public static CommercialDocumentSnapshot parse(SyncEventRequest request) {
        if (request == null || !"DOCUMENTO".equals(request.entityType()) || request.payload() == null) {
            throw invalid("payload");
        }
        Map<String, Object> payload = request.payload();
        if (integer(payload, "schemaVersion") != SCHEMA_VERSION) throw invalid("schemaVersion");
        long revision = integer(payload, "sourceRevision");
        if (revision < 0) throw invalid("sourceRevision");
        if (request.operation() == null || !OPERATIONS.contains(request.operation())) throw invalid("operation");
        String type = text(payload, "tipo", 24);
        if (!TYPES.contains(type)) throw invalid("tipo");
        String status = text(payload, "estado", 16);
        if (!STATUSES.contains(status)) throw invalid("estado");
        if ((request.operation() == SyncOperation.ANULAR) != "ANULADO".equals(status)) {
            throw invalid("estado");
        }
        String currency = text(payload, "moneda", 3);
        if (!currency.matches("[A-Z]{3}")) throw invalid("moneda");
        return new CommercialDocumentSnapshot(revision, type, status, text(payload, "numero", 32),
                date(payload, "fecha"), currency, money(payload, "subtotal"), money(payload, "impuestos"),
                money(payload, "total"), optionalUuid(payload, "clienteId"),
                optionalUuid(payload, "creadoPor"), optionalUuid(payload, "confirmadoPor"),
                optionalInstant(payload, "creadoEn"), optionalInstant(payload, "confirmadoEn"),
                optionalUuid(payload, "terminalOrigenId"));
    }

    /** Only absence or a numeric exact 1 belongs to the pre-projection legacy contract. */
    static boolean isLegacy(Map<String, Object> payload) {
        if (payload == null || !payload.containsKey("schemaVersion")) return true;
        Object value = payload.get("schemaVersion");
        if (!(value instanceof Number number) || value instanceof Float || value instanceof Double) return false;
        try {
            return new BigDecimal(number.toString()).compareTo(BigDecimal.ONE) == 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static long integer(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof Number number) || value instanceof Float || value instanceof Double) {
            throw invalid(key);
        }
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw invalid(key);
        }
    }

    private static String text(Map<String, Object> payload, String key, int maxLength) {
        Object raw = payload.get(key);
        if (!(raw instanceof String value) || value.isBlank() || value.length() > maxLength
                || !value.equals(value.strip()) || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(key);
        }
        return value;
    }

    private static LocalDate date(Map<String, Object> payload, String key) {
        String value = text(payload, key, 10);
        if (!DATE_TEXT.matcher(value).matches()) throw invalid(key);
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (parsed.getYear() < 1) throw invalid(key);
            return parsed;
        } catch (DateTimeParseException exception) {
            throw invalid(key);
        }
    }

    private static BigDecimal money(Map<String, Object> payload, String key) {
        Object raw = payload.get(key);
        if ((!(raw instanceof String) && !(raw instanceof Number)) || raw instanceof Float || raw instanceof Double) {
            throw invalid(key);
        }
        String value = raw.toString();
        // Bounded plain decimals avoid locale conversion, exponent expansion and silent numeric(19,2) rounding.
        if (value.length() > 64 || !MONEY_TEXT.matcher(value).matches()) throw invalid(key);
        try {
            BigDecimal parsed = new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
            if (parsed.precision() > 19) throw invalid(key);
            return parsed;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw invalid(key);
        }
    }

    private static UUID optionalUuid(Map<String, Object> payload, String key) {
        if (payload.get(key) == null) return null;
        String value = text(payload, key, 36);
        if (!UUID_TEXT.matcher(value).matches()) throw invalid(key);
        return UUID.fromString(value);
    }

    private static Instant optionalInstant(Map<String, Object> payload, String key) {
        if (payload.get(key) == null) return null;
        String value = text(payload, key, 40);
        try {
            Instant parsed = Instant.parse(value);
            if (parsed.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                    || !parsed.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) throw invalid(key);
            return parsed;
        } catch (DateTimeParseException exception) {
            throw invalid(key);
        }
    }

    static ResponseStatusException invalid(String field) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campo documental invalido: " + field);
    }
}

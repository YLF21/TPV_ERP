package com.tpverp.saas.document;

import static com.tpverp.saas.document.CommercialDocumentSnapshot.invalid;

import com.tpverp.saas.sync.SyncEventRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Optional query metadata from a full v2 snapshot; absent values are never inferred. */
public record CommercialDocumentQueryMetadata(
        UUID cancelledByLocalId,
        Instant sourceCancelledAt,
        LocalDate dueDate,
        Boolean settledByOrigin,
        boolean relationshipsComplete,
        List<Relation> relationships,
        String userName,
        String terminalName) {

    private static final Set<String> RELATION_TYPES = Set.of("FACTURA_DE", "RECTIFICA", "COMPENSA");
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DATE_TEXT = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Instant MIN_INSTANT = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_INSTANT = Instant.parse("+10000-01-01T00:00:00Z");

    public CommercialDocumentQueryMetadata {
        relationships = List.copyOf(relationships);
    }

    /** Called after the header validates the v2 envelope. */
    public static CommercialDocumentQueryMetadata parse(SyncEventRequest request) {
        if (request == null || request.payload() == null || request.entityId() == null) {
            throw invalid("payload");
        }
        Map<String, Object> payload = request.payload();
        Object rawRelations = payload.get("relaciones");
        List<Relation> relationships = new ArrayList<>();
        if (rawRelations != null) {
            if (!(rawRelations instanceof List<?> values)) throw invalid("relaciones");
            Set<Relation> unique = new HashSet<>();
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> relation)) throw invalid("relaciones");
                Object rawType = relation.get("tipo");
                if (!(rawType instanceof String type) || !RELATION_TYPES.contains(type)) {
                    throw invalid("relaciones.tipo");
                }
                UUID origin = uuid(relation.get("origenId"), "relaciones.origenId");
                if (origin.equals(request.entityId())) throw invalid("relaciones.origenId");
                Relation parsed = new Relation(type, origin);
                if (!unique.add(parsed)) throw invalid("relaciones");
                relationships.add(parsed);
            }
        }
        return new CommercialDocumentQueryMetadata(optionalUuid(payload.get("anuladoPor"), "anuladoPor"),
                optionalInstant(payload.get("anuladoEn"), "anuladoEn"),
                optionalDate(payload.get("fechaVencimiento"), "fechaVencimiento"),
                optionalBoolean(payload.get("settledByOrigin"), "settledByOrigin"),
                rawRelations != null, relationships,
                optionalLabel(payload.get("usuarioNombre"), "usuarioNombre"),
                optionalLabel(payload.get("terminalOrigenNombre"), "terminalOrigenNombre"));
    }

    private static String optionalLabel(Object raw, String field) {
        if (raw == null) return null;
        if (!(raw instanceof String value) || value.length() > 255
                || value.codePoints().anyMatch(Character::isISOControl)) throw invalid(field);
        return value.isBlank() ? null : value.strip();
    }

    private static UUID optionalUuid(Object raw, String field) {
        return raw == null ? null : uuid(raw, field);
    }

    private static UUID uuid(Object raw, String field) {
        if (!(raw instanceof String value) || !UUID_TEXT.matcher(value).matches()) throw invalid(field);
        return UUID.fromString(value);
    }

    private static Instant optionalInstant(Object raw, String field) {
        if (raw == null) return null;
        if (!(raw instanceof String value) || value.length() > 40) throw invalid(field);
        try {
            Instant parsed = Instant.parse(value);
            if (parsed.isBefore(MIN_INSTANT) || !parsed.isBefore(MAX_INSTANT)) throw invalid(field);
            return parsed;
        } catch (DateTimeParseException exception) {
            throw invalid(field);
        }
    }

    private static LocalDate optionalDate(Object raw, String field) {
        if (raw == null) return null;
        if (!(raw instanceof String value) || !DATE_TEXT.matcher(value).matches()) throw invalid(field);
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (parsed.getYear() < 1) throw invalid(field);
            return parsed;
        } catch (DateTimeParseException exception) {
            throw invalid(field);
        }
    }

    private static Boolean optionalBoolean(Object raw, String field) {
        if (raw == null) return null;
        if (!(raw instanceof Boolean value)) throw invalid(field);
        return value;
    }

    public record Relation(String type, UUID originDocumentId) { }
}

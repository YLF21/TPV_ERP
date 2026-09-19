package com.tpverp.saas.document;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Optional read projection: malformed historical lines must not reject an otherwise valid header. */
record CommercialDocumentLines(Status status, UUID warehouseLocalId, List<Line> lines) {
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DECIMAL = Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?");
    private static final Set<String> LINE_TYPES = Set.of("PRODUCT", "PROMOTION", "PROMOTIONAL_COUPON",
            "MEMBER_BALANCE", "MANUAL_DISCOUNT", "DOCUMENT_DISCOUNT", "RETURN_ADJUSTMENT");

    CommercialDocumentLines { lines = List.copyOf(lines); }

    static CommercialDocumentLines parse(Map<?, ?> payload) {
        if (payload == null) return invalid();
        try {
            UUID warehouse = optionalUuid(payload.get("almacenId"));
            Object raw = payload.get("lineas");
            if (raw == null) return new CommercialDocumentLines(Status.MISSING, warehouse, List.of());
            if (!(raw instanceof List<?> entries)) return invalid();
            var positions = new HashSet<Integer>();
            var lines = new ArrayList<Line>(entries.size());
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> fields)) return invalid();
                int position = decimal(fields.get("posicion")).intValueExact();
                if (position < 1 || !positions.add(position)) return invalid();
                String type = optionalText(fields.get("tipoLinea"));
                if (!LINE_TYPES.contains(type == null ? "" : type)) return invalid();
                String code = optionalText(fields.get("codigo"));
                // The source document stores codigo as varchar(128); keep invalid legacy text out of the index.
                if (code != null && code.codePointCount(0, code.length()) > 128) return invalid();
                if ("PRODUCT".equals(type) && (code == null || code.isBlank())) return invalid();
                lines.add(new Line(position, optionalUuid(fields.get("productoId")), type, code,
                        optionalText(fields.get("nombre")), optionalText(fields.get("tarifa")), decimal(fields.get("cantidad")),
                        decimal(fields.get("precioUnitario")), decimal(fields.get("descuento")),
                        decimal(fields.get("total"))));
            }
            return new CommercialDocumentLines(Status.READY, warehouse, lines);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            return invalid();
        }
    }

    static CommercialDocumentLines invalid() {
        return new CommercialDocumentLines(Status.INVALID, null, List.of());
    }

    private static String optionalText(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof String value) || value.indexOf('\0') >= 0) throw new IllegalArgumentException();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IllegalArgumentException();
                }
            } else if (Character.isLowSurrogate(current)) throw new IllegalArgumentException();
        }
        return value;
    }

    private static UUID optionalUuid(Object raw) {
        String value = optionalText(raw);
        if (value == null) return null;
        if (!UUID_TEXT.matcher(value).matches()) throw new IllegalArgumentException();
        return UUID.fromString(value);
    }

    private static BigDecimal decimal(Object raw) {
        if (raw instanceof BigDecimal value) {
            if (value.precision() > 1024 || value.scale() < -1024 || value.scale() > 1024) {
                throw new IllegalArgumentException();
            }
            return value;
        }
        if (!(raw instanceof String || raw instanceof Number) || raw instanceof Float || raw instanceof Double) {
            throw new IllegalArgumentException();
        }
        // Bound parsing, not precision/scale: accepted source decimals are stored unchanged as NUMERIC.
        String value = raw.toString();
        if (value.length() > 1024 || !DECIMAL.matcher(value).matches()) throw new IllegalArgumentException();
        return new BigDecimal(value);
    }

    enum Status { READY, MISSING, INVALID }
    record Line(int position, UUID productLocalId, String type, String code, String name, String priceTariff,
                BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPercent, BigDecimal total) { }
}

package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Filters and orders the complete customer history before selecting a bounded ID page. */
@Repository
public class CustomerDocumentReportQueryRepository {

    private static final String OCCURRED_AT = "coalesce(document.confirmado_en, document.creado_en)";
    private final NamedParameterJdbcTemplate jdbc;

    public CustomerDocumentReportQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page findPage(UUID storeId, UUID customerId, Collection<CommercialDocumentType> types,
            CustomerDocumentReportFilter filter, String encodedCursor, int limit) {
        if (storeId == null || customerId == null || limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Consulta de documentos de cliente no valida");
        }
        var sort = sort(filter.effectiveSortBy());
        var direction = filter.effectiveDirection();
        var comparator = direction.equals("asc") ? ">" : "<";
        var context = context(storeId, customerId, types, filter);
        var cursor = decodeCursor(encodedCursor, context);
        if (types.isEmpty()) return new Page(List.of(), null, false);
        var parameters = new MapSqlParameterSource()
                .addValue("storeId", storeId)
                .addValue("customerId", customerId)
                .addValue("types", types.stream().map(Enum::name).toList())
                .addValue("limit", limit + 1);
        // Expressions/directions are selected from a closed allowlist; request values are bound.
        var sql = new StringBuilder("""
                select document.id, %s as sort_value, %s as occurred_at
                from documento document
                left join terminal terminal
                  on terminal.id = document.terminal_origen_id and terminal.tienda_id = document.tienda_id
                left join usuario actor
                  on actor.id = coalesce(document.confirmado_por, document.creado_por)
                 and (actor.tienda_id is null or actor.tienda_id = document.tienda_id)
                where document.tienda_id = :storeId
                  and document.cliente_id = :customerId
                  and document.tipo in (:types)
                """.formatted(sort.expression(), OCCURRED_AT));
        if (filter.search() != null) {
            sql.append(" and lower(document.numero) like :search escape '!'\n");
            var term = filter.search().toLowerCase(Locale.ROOT)
                    .replace("!", "!!").replace("%", "!%").replace("_", "!_");
            parameters.addValue("search", "%" + term + "%");
        }
        if (filter.status() != null) {
            sql.append(" and document.estado = :status\n");
            parameters.addValue("status", filter.status().name());
        }
        if (filter.dateFrom() != null) {
            sql.append(" and document.fecha >= :dateFrom\n");
            parameters.addValue("dateFrom", filter.dateFrom());
        }
        if (filter.dateTo() != null) {
            sql.append(" and document.fecha <= :dateTo\n");
            parameters.addValue("dateTo", filter.dateTo());
        }
        if (cursor != null) {
            parameters.addValue("cursorId", cursor.id());
            if (cursor.nullValue()) {
                if (sort.date()) throw invalidCursor();
                sql.append(" and ").append(sort.expression()).append(" is null and document.id ")
                        .append(comparator).append(" :cursorId\n");
            } else {
                parameters.addValue("cursorValue", sort.value(cursor.value()));
                sql.append(" and (").append(sort.expression()).append(" is null or ")
                        .append(sort.expression()).append(" ").append(comparator)
                        .append(" :cursorValue or (").append(sort.expression()).append(" = :cursorValue and ");
                if (sort.date()) {
                    parameters.addValue("cursorOccurredAt", Timestamp.from(parseInstant(cursor.occurredAt())));
                    sql.append("(").append(OCCURRED_AT).append(" ").append(comparator)
                            .append(" :cursorOccurredAt or (").append(OCCURRED_AT)
                            .append(" = :cursorOccurredAt and document.id ").append(comparator)
                            .append(" :cursorId))");
                } else {
                    sql.append("document.id ").append(comparator).append(" :cursorId");
                }
                sql.append("))\n");
            }
        }
        sql.append(" order by (").append(sort.expression()).append(" is null) asc, ")
                .append(sort.expression()).append(" ").append(direction);
        if (sort.date()) sql.append(", ").append(OCCURRED_AT).append(" ").append(direction);
        sql.append(", document.id ").append(direction).append(" limit :limit");
        var rows = jdbc.query(sql.toString(), parameters, (result, rowNumber) -> {
            var value = result.getObject("sort_value");
            return new Cursor(value == null,
                    value == null ? "" : sort.decimal()
                            ? result.getBigDecimal("sort_value").toPlainString()
                            : result.getString("sort_value"),
                    result.getTimestamp("occurred_at").toInstant().toString(),
                    result.getObject("id", UUID.class));
        });
        var hasMore = rows.size() > limit;
        var visible = hasMore ? rows.subList(0, limit) : rows;
        return new Page(visible.stream().map(Cursor::id).toList(),
                hasMore ? encodeCursor(visible.getLast(), context) : null, hasMore);
    }

    private static Sort sort(String column) {
        var expression = switch (column) {
            case "number" -> "lower(document.numero)";
            case "date" -> "document.fecha";
            case "type" -> "document.tipo";
            case "status" -> "document.estado";
            case "base" -> "document.base_total";
            case "tax" -> "document.impuesto_total";
            case "total" -> "document.total";
            case "terminal" -> "lower(nullif(terminal.nombre, ''))";
            case "user" -> "lower(nullif(actor.user_name, ''))";
            default -> throw new IllegalArgumentException("Columna de ordenacion de documentos no valida");
        };
        return new Sort(expression, column.equals("date"),
                column.equals("base") || column.equals("tax") || column.equals("total"));
    }

    private static String context(UUID storeId, UUID customerId,
            Collection<CommercialDocumentType> types, CustomerDocumentReportFilter filter) {
        return encode(storeId + "|" + customerId + "|"
                + types.stream().map(Enum::name).sorted().collect(Collectors.joining(",")) + "|"
                + encode(filter.search() == null ? "" : filter.search().toLowerCase(Locale.ROOT)) + "|"
                + filter.status() + "|" + filter.dateFrom() + "|" + filter.dateTo() + "|"
                + filter.effectiveSortBy() + "|" + filter.effectiveDirection());
    }

    private static String encodeCursor(Cursor cursor, String context) {
        return "cdr1." + context + "." + (cursor.nullValue() ? "n" : "v") + "."
                + encode(cursor.value()) + "." + encode(cursor.occurredAt()) + "." + cursor.id();
    }

    private static Cursor decodeCursor(String cursor, String context) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            if (cursor.length() > 4096) throw invalidCursor();
            var parts = cursor.split("\\.", -1);
            if (parts.length != 6 || !parts[0].equals("cdr1") || !parts[1].equals(context)
                    || !(parts[2].equals("n") || parts[2].equals("v"))) {
                throw invalidCursor();
            }
            return new Cursor(parts[2].equals("n"), decode(parts[3]), decode(parts[4]),
                    UUID.fromString(parts[5]));
        } catch (IllegalArgumentException error) {
            throw invalidCursor();
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException error) {
            throw invalidCursor();
        }
    }

    private static IllegalArgumentException invalidCursor() {
        return new IllegalArgumentException("Cursor de documentos no valido para los filtros y ordenacion actuales");
    }

    private record Sort(String expression, boolean date, boolean decimal) {
        Object value(String text) {
            try {
                return date ? LocalDate.parse(text) : decimal ? new BigDecimal(text) : text;
            } catch (RuntimeException error) {
                throw invalidCursor();
            }
        }
    }

    private record Cursor(boolean nullValue, String value, String occurredAt, UUID id) {
    }

    public record Page(List<UUID> ids, String nextCursor, boolean hasMore) {
    }
}

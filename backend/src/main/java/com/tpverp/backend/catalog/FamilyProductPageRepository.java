package com.tpverp.backend.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Store-scoped keyset query for the product pane of the family hierarchy.
 * Every SQL fragment is selected from a closed allowlist; request values are
 * never interpolated into the statement.
 */
@Repository
public class FamilyProductPageRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public FamilyProductPageRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<FamilyProductPageRow> findPage(
            UUID storeId,
            ScopeKind scopeKind,
            UUID scopeId,
            String sortBy,
            String sortDirection,
            FamilyProductPageCursor cursor,
            int limit) {
        SortDefinition sort = sortDefinition(sortBy, sortDirection);
        String scopeColumn = switch (scopeKind) {
            case FAMILY -> "p.familia_id";
            case SUBFAMILY -> "p.subfamilia_id";
        };
        String expression = sort.expression();
        String comparator = sort.comparator();
        var parameters = new MapSqlParameterSource()
                .addValue("storeId", storeId)
                .addValue("scopeId", scopeId)
                .addValue("limit", limit);
        var sql = new StringBuilder(("""
                select p.id, p.version, p.imagen_id, p.imagen_hash,
                       coalesce(code.valor, '') as code,
                       coalesce(barcode.valor, '') as barcode,
                       p.nombre, sale.importe as sale_price,
                       p.familia_id, p.subfamilia_id, p.activo,
                       %s as sort_value
                from producto p
                left join producto_identificador code
                  on code.producto_id = p.id and code.tipo = 'CODIGO'
                left join producto_identificador barcode
                  on barcode.producto_id = p.id and barcode.tipo = 'CODIGO_BARRAS'
                left join producto_precio sale
                  on sale.producto_id = p.id and sale.tarifa = 'VENTA'
                where p.tienda_id = :storeId
                  and %s = :scopeId
                """).formatted(expression, scopeColumn));
        if (cursor != null) {
            parameters.addValue("cursorId", cursor.id());
            if (cursor.nullSortValue()) {
                sql.append(" and ").append(expression).append(" is null")
                        .append(" and p.id ").append(comparator).append(" :cursorId\n");
            } else {
                parameters.addValue("cursorValue", cursorValue(sort.valueType(), cursor.value()));
                sql.append(" and (").append(expression).append(" is null")
                        .append(" or ").append(expression).append(" ").append(comparator)
                        .append(" :cursorValue")
                        .append(" or (").append(expression).append(" = :cursorValue")
                        .append(" and p.id ").append(comparator).append(" :cursorId))\n");
            }
        }
        sql.append(" order by (").append(expression).append(" is null) asc, ")
                .append(expression).append(" ").append(sort.direction())
                .append(", p.id ").append(sort.direction()).append(" limit :limit");
        return jdbc.query(sql.toString(), parameters, (result, rowNumber) -> {
            Object rawSortValue = result.getObject("sort_value");
            String cursorValue = rawSortValue == null ? null : switch (sort.valueType()) {
                case TEXT -> result.getString("sort_value");
                case DECIMAL -> result.getBigDecimal("sort_value").toPlainString();
            };
            return new FamilyProductPageRow(
                    result.getObject("id", UUID.class),
                    result.getLong("version"),
                    result.getString("imagen_id"),
                    result.getString("imagen_hash"),
                    result.getString("code"),
                    result.getString("barcode"),
                    result.getString("nombre"),
                    result.getBigDecimal("sale_price"),
                    result.getObject("familia_id", UUID.class),
                    result.getObject("subfamilia_id", UUID.class),
                    result.getBoolean("activo"),
                    rawSortValue == null,
                    cursorValue);
        });
    }

    static SortDefinition sortDefinition(String sortBy, String sortDirection) {
        String expression = switch (sortBy) {
            case "code" -> "coalesce(code.valor, barcode.valor)";
            case "name" -> "lower(p.nombre)";
            case "salePrice" -> "sale.importe";
            default -> throw new IllegalArgumentException(
                    "Columna de ordenacion de productos de familia no valida");
        };
        SortValueType valueType = "salePrice".equals(sortBy)
                ? SortValueType.DECIMAL : SortValueType.TEXT;
        String direction = switch (sortDirection) {
            case "asc" -> "asc";
            case "desc" -> "desc";
            default -> throw new IllegalArgumentException(
                    "Direccion de ordenacion de productos de familia no valida");
        };
        return new SortDefinition(expression, direction,
                "asc".equals(direction) ? ">" : "<", valueType);
    }

    private static Object cursorValue(SortValueType type, String value) {
        if (value == null) {
            throw new IllegalArgumentException("Cursor de productos de familia no valido");
        }
        try {
            return switch (type) {
                case TEXT -> value;
                case DECIMAL -> new BigDecimal(value);
            };
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Cursor de productos de familia no valido", exception);
        }
    }

    enum ScopeKind {
        FAMILY("family"),
        SUBFAMILY("subfamily");

        private final String cursorValue;

        ScopeKind(String cursorValue) {
            this.cursorValue = cursorValue;
        }

        String cursorValue() {
            return cursorValue;
        }

        static ScopeKind fromCursorValue(String value) {
            return switch (value) {
                case "family" -> FAMILY;
                case "subfamily" -> SUBFAMILY;
                default -> throw new IllegalArgumentException(
                        "Cursor de productos de familia no valido");
            };
        }
    }

    enum SortValueType {
        TEXT("text"),
        DECIMAL("decimal");

        private final String cursorValue;

        SortValueType(String cursorValue) {
            this.cursorValue = cursorValue;
        }

        String cursorValue() {
            return cursorValue;
        }
    }

    record SortDefinition(
            String expression,
            String direction,
            String comparator,
            SortValueType valueType) {
    }

    record FamilyProductPageCursor(boolean nullSortValue, String value, UUID id) {
    }

    record FamilyProductPageRow(
            UUID id,
            long version,
            String imageId,
            String imageHash,
            String code,
            String barcode,
            String name,
            BigDecimal salePrice,
            UUID familyId,
            UUID subfamilyId,
            boolean active,
            boolean nullSortValue,
            String sortValue) {
    }
}

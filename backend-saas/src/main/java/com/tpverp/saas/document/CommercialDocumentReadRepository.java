package com.tpverp.saas.document;

import static com.tpverp.saas.document.CommercialDocumentQuery.*;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads current received snapshots, never interpreting document amounts as net sales or debt. */
@Repository
public class CommercialDocumentReadRepository {
    private static final String NULL_UUID = "cast(null as uuid)";
    private static final String FROM = """
            from saas_commercial_document d
            join saas_store s on s.id = d.store_id and s.company_id = d.company_id
            left join saas_customer_identity_link l
              on l.company_id = d.company_id
             and l.installation_id = d.source_installation_id
             and l.local_customer_id = d.customer_local_id
            left join saas_erp_customer c
              on c.id = l.customer_id and c.company_id = d.company_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    private static final String ROW_SELECT = """
            select d.company_id, d.store_id, s.code as store_code, d.source_document_id, d.source_installation_id,
                   d.source_revision, d.document_type, d.document_status, d.document_number,
                   d.business_date, d.currency, d.subtotal, d.tax_total, d.total,
                   d.customer_local_id, c.id as customer_id, c.code as customer_code,
                   c.name as customer_name, c.tax_id as customer_tax_id,
                   d.created_by_local_id, d.confirmed_by_local_id, d.origin_terminal_local_id,
                   d.source_created_at, d.source_confirmed_at, d.cancelled_by_local_id,
                   d.source_cancelled_at, d.due_date, d.settled_by_origin, d.relationships_complete,
                   d.user_name, d.terminal_name
            """;

    public CommercialDocumentReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> page(Scope scope, Filter filter, Order order,
            CommercialDocumentCursor after, int limit) {
        Objects.requireNonNull(order, "order");
        requireLimit(limit, 201);
        var query = filtered(scope, filter);
        var parameters = query.parameters().addValue("limit", limit);
        String primary = sortExpression(order.field());
        String comparator = order.direction() == Direction.ASC ? ">" : "<";
        boolean nullableLabel = order.field() == SortField.TERMINAL || order.field() == SortField.USER;
        var sql = new StringBuilder(ROW_SELECT).append(query.sql());
        if (after != null) {
            parameters.addValue("afterValue", after.jdbcValue(order))
                    .addValue("afterStore", after.storeId())
                    .addValue("afterDocument", after.documentId());
            // Missing labels sort last in either direction, with a typed empty-string boundary.
            if (nullableLabel && after.sortValue().isEmpty()) {
                sql.append(" and ").append(primary).append(" = '' and (d.store_id, d.source_document_id)")
                        .append(" > (:afterStore, :afterDocument)\n");
            } else {
                sql.append(" and (");
                if (nullableLabel) sql.append(primary).append(" = '' or (").append(primary).append(" <> '' and ");
                sql.append("(").append(primary).append(" ").append(comparator)
                        .append(" :afterValue or (").append(primary).append(" = :afterValue")
                        .append(" and (d.store_id, d.source_document_id) > (:afterStore, :afterDocument)))");
                if (nullableLabel) sql.append(")");
                sql.append(")\n");
            }
        }
        sql.append(" order by ");
        if (nullableLabel) sql.append("(").append(primary).append(" = '') asc, ");
        sql.append(primary).append(" ").append(order.direction().name())
                .append(", d.store_id asc, d.source_document_id asc limit :limit");
        return jdbc.query(sql.toString(), parameters, (result, index) -> row(result));
    }

    /** Resolves only the requested composite keys inside the same trusted filters. */
    public List<Row> selected(Scope scope, Filter filter, List<CommercialDocumentApi.DocumentKey> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > 200) throw invalid("documentKeys");
        var query = filtered(scope, filter);
        var sql = new StringBuilder(ROW_SELECT).append(query.sql()).append(" and (");
        for (int index = 0; index < keys.size(); index++) {
            var key = keys.get(index);
            if (key == null || key.storeId() == null || key.documentId() == null) throw invalid("documentKeys");
            if (index > 0) sql.append(" or ");
            sql.append("(d.store_id = :keyStore").append(index).append(" and d.source_document_id = :keyId")
                    .append(index).append(")");
            query.parameters().addValue("keyStore" + index, key.storeId()).addValue("keyId" + index, key.documentId());
        }
        sql.append(")");
        return jdbc.query(sql.toString(), query.parameters(), (result, index) -> row(result));
    }

    public List<Total> aggregate(Scope scope, Filter filter, Aggregation grouping, int limit) {
        Objects.requireNonNull(grouping, "grouping");
        requireLimit(limit, 2001);
        var query = filtered(scope, filter);
        var dimensions = grouping.dimensions();
        boolean customer = dimensions.contains(Dimension.CUSTOMER);
        boolean createdBy = dimensions.contains(Dimension.CREATED_BY);
        boolean confirmedBy = dimensions.contains(Dimension.CONFIRMED_BY);
        String created = createdBy ? "d.created_by_local_id" : NULL_UUID;
        String confirmed = confirmedBy ? "d.confirmed_by_local_id" : NULL_UUID;
        String actorInstallation = actorInstallation(createdBy, confirmedBy);
        String unresolvedInstallation = customer
                ? "case when c.id is null and d.customer_local_id is not null then d.source_installation_id end"
                : NULL_UUID;
        String unresolvedCustomer = customer
                ? "case when c.id is null then d.customer_local_id end" : NULL_UUID;

        // The first eleven columns define the entire group. No child-row joins can multiply amounts.
        String sql = """
                select %s as period_start,
                       %s as store_id,
                       %s as customer_id,
                       %s as unresolved_customer_installation_id,
                       %s as unresolved_customer_local_id,
                       %s as actor_installation_id,
                       %s as created_by_local_id,
                       %s as confirmed_by_local_id,
                       d.document_type collate "C" as document_type,
                       d.document_status collate "C" as document_status,
                       d.currency collate "C" as currency,
                       count(*) as document_count,
                       sum(d.subtotal) as subtotal,
                       sum(d.tax_total) as tax_total,
                       sum(d.total) as total
                """.formatted(periodExpression(grouping.period()),
                        dimensions.contains(Dimension.STORE) ? "d.store_id" : NULL_UUID,
                        customer ? "c.id" : NULL_UUID,
                        unresolvedInstallation, unresolvedCustomer, actorInstallation, created, confirmed)
                + query.sql() + """
                 group by 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11
                 order by 1 asc nulls last, 2 asc nulls last, 3 asc nulls last,
                          4 asc nulls last, 5 asc nulls last, 6 asc nulls last,
                          7 asc nulls last, 8 asc nulls last, 9 asc, 10 asc, 11 asc
                 limit :limit
                """;
        // The facade requests one extra group and rejects excess rather than returning partial totals.
        return jdbc.query(sql, query.parameters().addValue("limit", limit),
                (result, index) -> total(result));
    }

    private static Filtered filtered(Scope scope, Filter filter) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(filter, "filter");
        var parameters = new MapSqlParameterSource()
                .addValue("companyId", scope.companyId())
                .addValue("types", filter.types().stream().map(Enum::name).sorted().toList())
                .addValue("statuses", filter.statuses().stream().map(Enum::name).sorted().toList());
        var sql = new StringBuilder(FROM).append("""
                where d.company_id = :companyId
                  and d.document_type in (:types)
                  and d.document_status in (:statuses)
                """);
        var stores = new HashSet<>(scope.companyWide() ? filter.storeIds() : scope.allowedStoreIds());
        if (!scope.companyWide() && !filter.storeIds().isEmpty()) stores.retainAll(filter.storeIds());
        if (!scope.companyWide() && stores.isEmpty()) {
            sql.append(" and 1 = 0\n");
        } else if (!stores.isEmpty()) {
            sql.append(" and d.store_id in (:storeIds)\n");
            parameters.addValue("storeIds", stores.stream().sorted().toList());
        }
        if (filter.customerId() != null) {
            sql.append(" and c.id = :customerId\n");
            parameters.addValue("customerId", filter.customerId());
        }
        if (filter.from() != null) {
            sql.append(" and d.business_date >= :dateFrom\n");
            parameters.addValue("dateFrom", Date.valueOf(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" and d.business_date <= :dateTo\n");
            parameters.addValue("dateTo", Date.valueOf(filter.to()));
        }
        if (filter.actor() != null) {
            sql.append(" and d.source_installation_id = :actorInstallationId\n")
                    .append(" and ").append(actorExpression(filter.actor().role())).append(" = :actorLocalId\n");
            parameters.addValue("actorInstallationId", filter.actor().installationId())
                    .addValue("actorLocalId", filter.actor().localUserId());
        }
        if (filter.numberContains() != null) {
            sql.append(" and d.document_number ilike :numberContains escape '!'\n");
            String literal = filter.numberContains().replace("!", "!!").replace("%", "!%").replace("_", "!_");
            parameters.addValue("numberContains", "%" + literal + "%");
        }
        return new Filtered(sql.toString(), parameters);
    }

    private static String sortExpression(SortField field) {
        return switch (field) {
            case DATE -> "d.business_date";
            case NUMBER -> "d.document_number collate \"C\"";
            case TYPE -> "d.document_type collate \"C\"";
            case STATUS -> "d.document_status collate \"C\"";
            case BASE -> "d.subtotal";
            case TAX -> "d.tax_total";
            case TOTAL -> "d.total";
            case TERMINAL -> "coalesce(d.terminal_name, '') collate \"C\"";
            case USER -> "coalesce(d.user_name, '') collate \"C\"";
            case STORE -> "s.code collate \"C\"";
            case CURRENCY -> "d.currency collate \"C\"";
        };
    }

    private static String actorExpression(ActorRole role) {
        return switch (role) {
            case CREATED_BY -> "d.created_by_local_id";
            case CONFIRMED_BY -> "d.confirmed_by_local_id";
        };
    }

    private static String actorInstallation(boolean createdBy, boolean confirmedBy) {
        if (!createdBy && !confirmedBy) return NULL_UUID;
        String present = createdBy && confirmedBy
                ? "d.created_by_local_id is not null or d.confirmed_by_local_id is not null"
                : (createdBy ? "d.created_by_local_id" : "d.confirmed_by_local_id") + " is not null";
        return "case when " + present + " then d.source_installation_id end";
    }

    private static String periodExpression(Period period) {
        return switch (period) {
            case NONE -> "cast(null as date)";
            case DAY -> "d.business_date";
            case MONTH -> "date_trunc('month', d.business_date::timestamp)::date";
            case QUARTER -> "date_trunc('quarter', d.business_date::timestamp)::date";
            case YEAR -> "date_trunc('year', d.business_date::timestamp)::date";
        };
    }

    private static Row row(ResultSet result) throws SQLException {
        return new Row(uuid(result, "company_id"), uuid(result, "store_id"), uuid(result, "source_document_id"),
                uuid(result, "source_installation_id"), result.getLong("source_revision"),
                Type.valueOf(result.getString("document_type")), Status.valueOf(result.getString("document_status")),
                result.getString("document_number"), date(result, "business_date"), result.getString("currency"),
                result.getBigDecimal("subtotal"), result.getBigDecimal("tax_total"), result.getBigDecimal("total"),
                uuid(result, "customer_local_id"), uuid(result, "customer_id"), result.getString("customer_code"),
                result.getString("customer_name"), result.getString("customer_tax_id"),
                uuid(result, "created_by_local_id"), uuid(result, "confirmed_by_local_id"),
                uuid(result, "origin_terminal_local_id"), instant(result, "source_created_at"),
                instant(result, "source_confirmed_at"), uuid(result, "cancelled_by_local_id"),
                instant(result, "source_cancelled_at"), date(result, "due_date"),
                result.getObject("settled_by_origin", Boolean.class), result.getBoolean("relationships_complete"),
                result.getString("user_name"), result.getString("terminal_name"), result.getString("store_code"));
    }

    private static Total total(ResultSet result) throws SQLException {
        var group = new Group(date(result, "period_start"), uuid(result, "store_id"), uuid(result, "customer_id"),
                uuid(result, "unresolved_customer_installation_id"), uuid(result, "unresolved_customer_local_id"),
                uuid(result, "actor_installation_id"), uuid(result, "created_by_local_id"),
                uuid(result, "confirmed_by_local_id"), Type.valueOf(result.getString("document_type")),
                Status.valueOf(result.getString("document_status")), result.getString("currency"));
        return new Total(group, result.getLong("document_count"), result.getBigDecimal("subtotal"),
                result.getBigDecimal("tax_total"), result.getBigDecimal("total"));
    }

    private static UUID uuid(ResultSet result, String column) throws SQLException {
        return result.getObject(column, UUID.class);
    }

    private static LocalDate date(ResultSet result, String column) throws SQLException {
        var value = result.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        var value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static void requireLimit(int limit, int maximum) {
        if (limit < 1 || limit > maximum) throw invalid("limit");
    }

    private record Filtered(String sql, MapSqlParameterSource parameters) { }
}

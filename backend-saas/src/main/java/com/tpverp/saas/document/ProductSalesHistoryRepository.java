package com.tpverp.saas.document;

import static com.tpverp.saas.document.ProductSalesHistoryApi.*;
import static com.tpverp.saas.document.ProductSalesHistoryQuery.*;

import com.tpverp.saas.document.CommercialDocumentQuery.Scope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bounded product reads; summaries aggregate in SQL independently of the detail page. */
@Repository
public class ProductSalesHistoryRepository {
    private static final String FROM = """
             from saas_commercial_document d
             join saas_commercial_document_line l on l.company_id=d.company_id and l.store_id=d.store_id
                  and l.source_document_id=d.source_document_id
             join saas_store s on s.company_id=d.company_id and s.id=d.store_id
             left join saas_customer_identity_link cl on cl.company_id=d.company_id
                  and cl.installation_id=d.source_installation_id and cl.local_customer_id=d.customer_local_id
             left join saas_erp_customer c on c.company_id=d.company_id and c.id=cl.customer_id
            """;
    // A conversion replaces its origin even when the invoice is outside the user's period/status filter.
    // COMPENSA links an exchange's independent new-sale and return lines, so both retain their sign.
    private static final String COUNTS = """
            (d.document_status <> 'ANULADO' and not (
              d.document_type in ('TICKET','ALBARAN_VENTA') and exists (
                select 1 from saas_commercial_document_relation r
                join saas_commercial_document invoice on invoice.company_id=r.company_id
                     and invoice.store_id=r.store_id and invoice.source_document_id=r.source_document_id
                where r.company_id=d.company_id and r.store_id=d.store_id
                  and r.origin_document_id=d.source_document_id and r.relation_type='FACTURA_DE'
                  and invoice.document_type='FACTURA_VENTA' and invoice.document_status <> 'ANULADO'
              )))
            """;
    private static final String SELECT = """
            select d.source_document_id, d.document_type, d.document_number, d.document_status,
                   d.business_date, coalesce(d.source_confirmed_at,d.source_created_at,
                       d.business_date::timestamp at time zone 'UTC') occurred_at,
                   d.store_id, s.code store_code, s.name store_name, d.source_installation_id,
                   l.line_position, l.product_code, l.product_name, l.quantity, l.unit_price,
                   l.discount_percent, l.line_total, d.currency, c.name customer_name,
                   d.user_name, null::text warehouse_name,
                   case when d.document_type='RECTIFICATIVA_VENTA' and l.price_tariff='DIFERENCIA'
                        then 0 else l.quantity end quantity_contribution,
            """ + COUNTS + " counts_as_sale ";
    // Economic rectifications retain their historical quantity and amount in detail, but move no sale units.
    private static final String AMOUNTS = """
            sum(case when counts_as_sale then greatest(quantity_contribution,0) else 0 end) quantity_sold,
            sum(case when counts_as_sale then greatest(-quantity_contribution,0) else 0 end) quantity_returned,
            sum(case when counts_as_sale then quantity_contribution else 0 end) net_quantity,
            sum(case when counts_as_sale then line_total else 0 end) net_amount
            """;
    private final NamedParameterJdbcTemplate jdbc;
    public ProductSalesHistoryRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    List<Item> page(Scope scope, Filter filter, Order order, ProductSalesHistoryCursor cursor, int limit) {
        if (limit < 1 || limit > 201) throw invalid("limit");
        Sql sql = filtered(scope, filter);
        String value = order.field().sql();
        StringBuilder query = new StringBuilder("with rows as (").append(SELECT).append(FROM)
                .append(sql.where()).append(") select * from rows");
        if (cursor != null) {
            query.append(" where (").append(value).append(order.ascending() ? " > " : " < ")
                    .append(":boundary or (").append(value).append(" = :boundary and ")
                    .append("(store_id,source_document_id,line_position) > (:afterStore,:afterDocument,:afterPosition)))");
            sql.params().addValue("boundary", cursor.jdbcValue(order)).addValue("afterStore", cursor.storeId())
                    .addValue("afterDocument", cursor.documentId()).addValue("afterPosition", cursor.position());
        }
        query.append(" order by ").append(value).append(order.ascending() ? " asc" : " desc")
                .append(",store_id asc,source_document_id asc,line_position asc limit :limit");
        return jdbc.query(query.toString(), sql.params().addValue("limit", limit), (row, index) -> item(row));
    }

    List<Total> totals(Scope scope, Filter filter) {
        Sql sql = filtered(scope, filter);
        return jdbc.query("with rows as (" + SELECT + FROM + sql.where() + ") select currency," + AMOUNTS
                        + " from rows group by currency order by currency", sql.params(),
                (row, index) -> new Total(row.getString("currency"), decimal(row,"quantity_sold"),
                        decimal(row,"quantity_returned"), decimal(row,"net_quantity"), decimal(row,"net_amount")));
    }

    List<Comparison> comparison(Scope scope, Filter filter) {
        Sql sql = filtered(scope, filter);
        List<Comparison> rows = jdbc.query("with rows as (" + SELECT + FROM + sql.where()
                        + ") select store_id,store_code,store_name,currency," + AMOUNTS
                        + " from rows group by store_id,store_code,store_name,currency"
                        + " order by net_quantity desc,store_code collate \"C\",store_id,currency limit 10001", sql.params(),
                (row, index) -> new Comparison(row.getObject("store_id", UUID.class), row.getString("store_code"),
                        row.getString("store_name"), row.getString("currency"), decimal(row,"quantity_sold"),
                        decimal(row,"quantity_returned"), decimal(row,"net_quantity"), decimal(row,"net_amount")));
        if (rows.size() > 10000) throw ProductSalesHistoryService.limit("PRODUCT_HISTORY_COMPARISON_LIMIT_EXCEEDED");
        return rows;
    }

    List<Store> stores(Scope scope) {
        var params = new MapSqlParameterSource("companyId", scope.companyId());
        String where = " where company_id=:companyId";
        if (!scope.companyWide()) {
            if (scope.allowedStoreIds().isEmpty()) return List.of();
            where += " and id in (:allowedStores)";
            params.addValue("allowedStores", scope.allowedStoreIds());
        }
        List<Store> rows = jdbc.query("select id,code,name from saas_store" + where
                        + " order by code collate \"C\",id limit 2001", params,
                (row, index) -> new Store(row.getObject("id", UUID.class), row.getString("code"), row.getString("name")));
        if (rows.size() > 2000) throw ProductSalesHistoryService.limit("PRODUCT_HISTORY_STORE_LIMIT_EXCEEDED");
        return rows;
    }

    /** Coverage is document-wide: an absent projection cannot establish whether it contains this product. */
    Coverage coverage(Scope scope, Filter filter) {
        Sql sql = headerFilter(scope, filter);
        return jdbc.queryForObject("""
                select count(*) filter (where d.line_projection_status <> 'READY' or not d.relationships_complete
                    or exists (select 1 from saas_commercial_document_relation r
                        where r.company_id=d.company_id and r.store_id=d.store_id
                        and r.source_document_id=d.source_document_id and not exists (
                            select 1 from saas_commercial_document origin where origin.company_id=r.company_id
                            and origin.store_id=r.store_id and origin.source_document_id=r.origin_document_id))) incomplete,
                    max(d.received_at) received_at from saas_commercial_document d
                """ + sql.where(), sql.params(), (row, index) -> new Coverage(row.getLong("incomplete"),
                        row.getTimestamp("received_at") == null ? null : row.getTimestamp("received_at").toInstant()));
    }

    private static Sql filtered(Scope scope, Filter filter) {
        Sql sql = headerFilter(scope, filter);
        return new Sql(sql.where() + " and d.line_projection_status='READY' and l.line_type='PRODUCT' and l.product_code=:productCode",
                sql.params().addValue("productCode", filter.productCode()));
    }
    private static Sql headerFilter(Scope scope, Filter filter) {
        var params = new MapSqlParameterSource("companyId", scope.companyId());
        var where = new StringBuilder(" where d.company_id=:companyId");
        var stores = new HashSet<>(scope.companyWide() ? filter.storeIds() : scope.allowedStoreIds());
        if (!scope.companyWide() && !filter.storeIds().isEmpty()) stores.retainAll(filter.storeIds());
        if (!scope.companyWide() && stores.isEmpty()) where.append(" and 1=0");
        if (!stores.isEmpty()) { where.append(" and d.store_id in (:stores)"); params.addValue("stores", stores); }
        if (filter.from() != null) { where.append(" and d.business_date >= :from"); params.addValue("from", java.sql.Date.valueOf(filter.from())); }
        if (filter.to() != null) { where.append(" and d.business_date <= :to"); params.addValue("to", java.sql.Date.valueOf(filter.to())); }
        if (filter.status() != null) { where.append(" and d.document_status=:status"); params.addValue("status", filter.status()); }
        return new Sql(where.toString(), params);
    }
    private static Item item(ResultSet row) throws SQLException {
        return new Item(row.getObject("source_document_id",UUID.class), row.getString("document_type"),
                row.getString("document_number"),row.getString("document_status"),row.getDate("business_date").toLocalDate(),
                row.getTimestamp("occurred_at").toInstant(),row.getObject("store_id",UUID.class),row.getString("store_code"),
                row.getString("store_name"),row.getObject("source_installation_id",UUID.class),row.getInt("line_position"),
                row.getString("product_code"),row.getString("product_name"),decimal(row,"quantity"),decimal(row,"unit_price"),
                decimal(row,"discount_percent"),decimal(row,"line_total"),row.getString("currency"),row.getString("customer_name"),
                row.getString("user_name"),row.getString("warehouse_name"),row.getBoolean("counts_as_sale"));
    }
    private static String decimal(ResultSet row, String column) throws SQLException { return row.getBigDecimal(column).toPlainString(); }
    record Coverage(long incompleteDocuments, Instant receivedAt) { }
    private record Sql(String where, MapSqlParameterSource params) { }
}

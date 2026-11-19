package com.tpverp.saas.inventory;

import static com.tpverp.saas.inventory.InventoryReport.*;

import com.tpverp.saas.analytics.AnalyticsScope;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class InventoryReportService {
    private final NamedParameterJdbcTemplate jdbc;

    public InventoryReportService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String STORE_SOURCE =
            """
             from saas_store s join saas_company c on c.id=s.company_id
             left join saas_installation i on i.store_id=s.id and i.company_id=c.id and i.active
             left join lateral (select * from saas_inventory_snapshot ss where ss.company_id=c.id and ss.store_id=s.id
                and ss.installation_id=i.id and ss.completed_at is not null
                order by ss.captured_at desc,ss.snapshot_id desc limit 1) cap on true
             where true
            """;
    private static final String TOTALS =
            """
             count(*) as stores,count(cap.id) reported_stores,coalesce(sum(cap.product_count),0) products,
             coalesce(sum(cap.active_product_count),0) active_products,coalesce(sum(cap.warehouse_count),0) warehouses,
             coalesce(sum(cap.balance_count),0) balances,coalesce(sum(cap.positive_count),0) positives,
             coalesce(sum(cap.zero_count),0) zeroes,coalesce(sum(cap.negative_count),0) negatives
            """;
    private static final String SNAPSHOT_SOURCE =
            """
             from saas_inventory_snapshot s join saas_installation i on i.id=s.installation_id
             where s.company_id=:company and s.store_id=:store and s.completed_at is not null
            """;
    private static final String NEWEST = " order by s.captured_at desc,s.snapshot_id desc";
    private static final String LIMIT = " limit :size offset :offset";

    public Overview overview(UUID company, UUID store, int page, int size) {
        return overview(company, store, null, OverviewSort.COMPANY, Direction.ASC, page, size);
    }

    public List<Province> provinces(UUID company, UUID store, String province) {
        var scope = new AnalyticsScope(company, store, province);
        scope.validate(jdbc);
        return jdbc.query(
                "select "
                        + AnalyticsScope.PROVINCE
                        + " province,"
                        + TOTALS
                        + STORE_SOURCE
                        + scope.stores()
                        + " group by 1 order by 1",
                scope.parameters(),
                (rs, n) -> new Province(rs.getString("province"), totals(rs)));
    }

    public Overview overview(
            UUID company,
            UUID store,
            String province,
            OverviewSort sort,
            Direction direction,
            int page,
            int size) {
        return readOverview(company, store, province, sort, direction, page, size, 200);
    }

    public Overview exportOverview(
            UUID company,
            UUID store,
            String province,
            OverviewSort sort,
            Direction direction,
            int limit) {
        return readOverview(company, store, province, sort, direction, 0, limit, 50_001);
    }

    private Overview readOverview(
            UUID company,
            UUID store,
            String province,
            OverviewSort sort,
            Direction direction,
            int page,
            int size,
            int maximumSize) {
        var scope = new AnalyticsScope(company, store, province);
        var params = params(company, store, page, size, maximumSize);
        params.addValues(scope.parameters().getValues());
        String source = STORE_SOURCE + scope.stores();
        String order =
                switch (sort) {
                    case COMPANY -> "lower(c.name)";
                    case STORE -> "lower(s.name)";
                    case PRODUCTS -> "cap.product_count";
                    case BALANCES -> "cap.balance_count";
                    case CAPTURED_AT -> "cap.captured_at";
                };
        var totals =
                jdbc.queryForObject("select " + TOTALS + source, params, (rs, n) -> totals(rs));
        var rows =
                jdbc.query(
                        "select c.id cid,c.name cname,s.id tid,s.name tname,true"
                                + " current_installation,cap.*"
                                + source
                                + " order by "
                                + order
                                + " "
                                + direction.name()
                                + " nulls last, c.id,lower(s.name),s.id"
                                + LIMIT,
                        params,
                        (rs, n) ->
                                new Store(
                                        uuid(rs, "cid"),
                                        rs.getString("cname"),
                                        uuid(rs, "tid"),
                                        rs.getString("tname"),
                                        snapshot(rs)));
        return new Overview(page, size, totals.stores(), totals, rows);
    }

    private static Totals totals(ResultSet rs) throws SQLException {
        return new Totals(
                rs.getLong("stores"),
                rs.getLong("reported_stores"),
                rs.getLong("products"),
                rs.getLong("active_products"),
                rs.getLong("warehouses"),
                rs.getLong("balances"),
                rs.getLong("positives"),
                rs.getLong("zeroes"),
                rs.getLong("negatives"));
    }

    public Page<Snapshot> history(UUID company, UUID store, int page, int size) {
        return history(company, store, HistorySort.CAPTURED_AT, Direction.DESC, page, size);
    }

    public Page<Snapshot> history(
            UUID company, UUID store, HistorySort sort, Direction direction, int page, int size) {
        return readHistory(company, store, sort, direction, page, size, 200);
    }

    public Page<Snapshot> exportHistory(
            UUID company, UUID store, HistorySort sort, Direction direction, int limit) {
        return readHistory(company, store, sort, direction, 0, limit, 50_001);
    }

    private Page<Snapshot> readHistory(
            UUID company,
            UUID store,
            HistorySort sort,
            Direction direction,
            int page,
            int size,
            int maximumSize) {
        String order =
                switch (sort) {
                    case CAPTURED_AT -> "s.captured_at";
                    case PRODUCTS -> "s.product_count";
                    case BALANCES -> "s.balance_count";
                };
        var params = params(company, store, page, size, maximumSize);
        return new Page<>(
                page,
                size,
                count(SNAPSHOT_SOURCE, params),
                jdbc.query(
                        "select s.*,i.active current_installation"
                                + SNAPSHOT_SOURCE
                                + " order by "
                                + order
                                + " "
                                + direction.name()
                                + ",s.snapshot_id desc"
                                + LIMIT,
                        params,
                        (rs, n) -> snapshot(rs)));
    }

    public Detail<Product> products(
            UUID company,
            UUID store,
            UUID snapshot,
            String search,
            Boolean active,
            int page,
            int size) {
        return products(
                company,
                store,
                snapshot,
                search,
                active,
                ProductSort.PRODUCT,
                Direction.ASC,
                page,
                size);
    }

    public Detail<Product> products(
            UUID company,
            UUID store,
            UUID snapshot,
            String search,
            Boolean active,
            ProductSort sort,
            Direction direction,
            int page,
            int size) {
        return readProducts(
                company, store, snapshot, search, active, sort, direction, page, size, 200);
    }

    public Detail<Product> exportProducts(
            UUID company,
            UUID store,
            UUID snapshot,
            String search,
            Boolean active,
            ProductSort sort,
            Direction direction,
            int limit) {
        return readProducts(
                company, store, snapshot, search, active, sort, direction, 0, limit, 50_001);
    }

    private Detail<Product> readProducts(
            UUID company,
            UUID store,
            UUID snapshot,
            String search,
            Boolean active,
            ProductSort sort,
            Direction direction,
            int page,
            int size,
            int maximumSize) {
        String order =
                switch (sort) {
                    case PRODUCT -> "lower(p.name)";
                    case CODE -> "lower(p.code)";
                    case TYPE -> "p.product_type";
                    case ACTIVE -> "p.active";
                };
        var params = params(company, store, page, size, maximumSize);
        String source =
                " from saas_inventory_product p where p.snapshot=:key"
                        + productFilters(params, search, active);
        return detail(
                params,
                snapshot,
                page,
                size,
                "select p.*",
                source,
                order + " " + direction.name() + " nulls last,p.product_id",
                (rs, n) ->
                        new Product(
                                uuid(rs, "product_id"),
                                rs.getString("code"),
                                rs.getString("name"),
                                rs.getString("barcode"),
                                rs.getString("secondary_barcode"),
                                rs.getBoolean("active"),
                                rs.getString("product_type"),
                                uuid(rs, "family_id"),
                                rs.getString("family_name"),
                                uuid(rs, "subfamily_id"),
                                rs.getString("subfamily_name")));
    }

    public Detail<Warehouse> warehouses(
            UUID company, UUID store, UUID snapshot, int page, int size) {
        return warehouses(
                company, store, snapshot, WarehouseSort.WAREHOUSE, Direction.ASC, page, size);
    }

    public Detail<Warehouse> warehouses(
            UUID company,
            UUID store,
            UUID snapshot,
            WarehouseSort sort,
            Direction direction,
            int page,
            int size) {
        return readWarehouses(company, store, snapshot, sort, direction, page, size, 200);
    }

    public Detail<Warehouse> exportWarehouses(
            UUID company,
            UUID store,
            UUID snapshot,
            WarehouseSort sort,
            Direction direction,
            int limit) {
        return readWarehouses(company, store, snapshot, sort, direction, 0, limit, 50_001);
    }

    private Detail<Warehouse> readWarehouses(
            UUID company,
            UUID store,
            UUID snapshot,
            WarehouseSort sort,
            Direction direction,
            int page,
            int size,
            int maximumSize) {
        String order = sort == WarehouseSort.ACTIVE ? "w.active" : "lower(w.name)";
        return detail(
                params(company, store, page, size, maximumSize),
                snapshot,
                page,
                size,
                "select w.*",
                " from saas_inventory_warehouse w where w.snapshot=:key",
                order + " " + direction.name() + ",w.warehouse_id",
                (rs, n) ->
                        new Warehouse(
                                uuid(rs, "warehouse_id"),
                                rs.getString("name"),
                                rs.getBoolean("active"),
                                rs.getBoolean("default_warehouse")));
    }

    public Detail<Balance> balances(
            UUID company,
            UUID store,
            UUID snapshot,
            String search,
            Boolean active,
            UUID product,
            UUID warehouse,
            int page,
            int size) {
        return balances(
                company,
                store,
                snapshot,
                search,
                active,
                product,
                warehouse,
                BalanceSort.PRODUCT,
                Direction.ASC,
                page,
                size);
    }

    public Detail<Balance> balances(
            UUID company,
            UUID store,
            UUID snapshot,
            String search,
            Boolean active,
            UUID product,
            UUID warehouse,
            BalanceSort sort,
            Direction direction,
            int page,
            int size) {
        return readBalances(
                company, store, snapshot, search, active, product, warehouse, sort, direction, page,
                size, 200);
    }

    public Detail<Balance> exportBalances(
            UUID company,
            UUID store,
            UUID snapshot,
            String search,
            Boolean active,
            UUID product,
            UUID warehouse,
            BalanceSort sort,
            Direction direction,
            int limit) {
        return readBalances(
                company, store, snapshot, search, active, product, warehouse, sort, direction, 0,
                limit, 50_001);
    }

    private Detail<Balance> readBalances(
            UUID company,
            UUID store,
            UUID snapshot,
            String search,
            Boolean active,
            UUID product,
            UUID warehouse,
            BalanceSort sort,
            Direction direction,
            int page,
            int size,
            int maximumSize) {
        String order =
                switch (sort) {
                    case PRODUCT -> "lower(p.name)";
                    case WAREHOUSE -> "lower(w.name)";
                    case QUANTITY -> "b.quantity";
                };
        var params = params(company, store, page, size, maximumSize);
        String source =
                """
                 from saas_inventory_balance b join saas_inventory_product p using(snapshot,product_id)
                 join saas_inventory_warehouse w using(snapshot,warehouse_id) where b.snapshot=:key
                """
                        + productFilters(params, search, active);
        if (product != null) {
            params.addValue("product", product);
            source += " and b.product_id=:product";
        }
        if (warehouse != null) {
            params.addValue("warehouse", warehouse);
            source += " and b.warehouse_id=:warehouse";
        }
        return detail(
                params,
                snapshot,
                page,
                size,
                "select b.*,p.code,p.name,p.product_type,p.active,w.name warehouse_name",
                source,
                order + " " + direction.name() + ",p.product_id,lower(w.name),w.warehouse_id",
                (rs, n) ->
                        new Balance(
                                uuid(rs, "product_id"),
                                rs.getString("code"),
                                rs.getString("name"),
                                rs.getString("product_type"),
                                rs.getBoolean("active"),
                                uuid(rs, "warehouse_id"),
                                rs.getString("warehouse_name"),
                                rs.getBigDecimal("quantity").toPlainString()));
    }

    private <T> Detail<T> detail(
            MapSqlParameterSource params,
            UUID snapshotId,
            int page,
            int size,
            String select,
            String source,
            String order,
            RowMapper<T> mapper) {
        params.addValue("snapshot", snapshotId);
        var selected =
                jdbc.query(
                        "select s.*,i.active current_installation"
                                + SNAPSHOT_SOURCE
                                + (snapshotId == null
                                        ? " and i.active"
                                        : " and s.snapshot_id=:snapshot")
                                + NEWEST
                                + " limit 1",
                        params,
                        (rs, n) -> Map.entry(rs.getLong("id"), snapshot(rs)));
        if (selected.isEmpty()) {
            if (snapshotId != null)
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Captura completa no encontrada");
            return new Detail<>(page, size, 0, null, List.of());
        }
        var target = selected.getFirst();
        params.addValue("key", target.getKey());
        return new Detail<>(
                page,
                size,
                count(source, params),
                target.getValue(),
                jdbc.query(select + source + " order by " + order + LIMIT, params, mapper));
    }

    private static String productFilters(
            MapSqlParameterSource params, String search, Boolean active) {
        String clause = "";
        if (search != null && !search.isBlank()) {
            if (search.length() > 255) throw invalid("Busqueda demasiado larga");
            params.addValue("search", search.strip());
            clause =
                    """
                     and (strpos(lower(p.name),lower(:search))>0 or strpos(lower(p.code),lower(:search))>0
                     or strpos(lower(p.barcode),lower(:search))>0 or strpos(lower(p.secondary_barcode),lower(:search))>0)
                    """;
        }
        if (active != null) {
            params.addValue("active", active);
            clause += " and p.active=:active";
        }
        return clause;
    }

    private MapSqlParameterSource params(
            UUID company, UUID store, int page, int size, int maximumSize) {
        if (page < 0 || size < 1 || size > maximumSize) throw invalid("Paginacion invalida");
        var params =
                new MapSqlParameterSource()
                        .addValue("company", company)
                        .addValue("store", store)
                        .addValue("size", size)
                        .addValue("offset", (long) page * size);
        if (store != null
                        && count(
                                        " from saas_store where id=:store"
                                                + (company == null
                                                        ? ""
                                                        : " and company_id=:company"),
                                        params)
                                == 0
                || store == null
                        && company != null
                        && count(" from saas_company where id=:company", params) == 0)
            throw invalid("Empresa o tienda invalida");
        return params;
    }

    private long count(String source, MapSqlParameterSource params) {
        return jdbc.queryForObject("select count(*)" + source, params, Long.class);
    }

    static Snapshot snapshot(ResultSet rs) throws SQLException {
        UUID id = uuid(rs, "snapshot_id");
        return id == null
                ? null
                : new Snapshot(
                        id,
                        uuid(rs, "installation_id"),
                        rs.getTimestamp("captured_at").toInstant(),
                        rs.getTimestamp("completed_at").toInstant(),
                        rs.getBoolean("current_installation"),
                        rs.getInt("product_count"),
                        rs.getInt("active_product_count"),
                        rs.getInt("warehouse_count"),
                        rs.getInt("balance_count"),
                        rs.getInt("positive_count"),
                        rs.getInt("zero_count"),
                        rs.getInt("negative_count"));
    }

    private static UUID uuid(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, UUID.class);
    }

    private static ResponseStatusException invalid(String error) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, error);
    }
}

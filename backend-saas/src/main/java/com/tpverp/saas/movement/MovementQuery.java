package com.tpverp.saas.movement;

import static com.tpverp.saas.movement.MovementReport.*;

import com.tpverp.saas.analytics.AnalyticsScope;

import java.util.Arrays;

final class MovementQuery {
    private MovementQuery() {}

    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$";
    private static final String INSTANT_PATTERN =
            "^[0-9]{4}-[0-9]{2}-[0-9]{2}T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]([.][0-9]{1,9})?Z$";

    // Keep the first receipt of each immutable local movement. Validate and detect conflicts before
    // applying any business filters, even if the conflicting copies describe different dates or
    // products.
    static String facts(AnalyticsScope scope, Filter filter) {
        String scopeFilter =
                (scope.company() == null ? "" : " and e.company_id=:companyId")
                        + (scope.store() == null ? "" : " and e.store_id=:storeId")
                        + (scope.province() == null
                                ? ""
                                : " and (" + AnalyticsScope.PROVINCE + ")=:province");
        String businessFilters =
                (filter.warehouseId() == null ? "" : " and warehouse_id=:warehouse")
                        + (filter.productId() == null ? "" : " and product_id=:product")
                        + (filter.type() == null ? "" : " and type=:type");
        return """
        with raw as materialized (
          select e.event_id,e.entity_id,e.company_id,e.store_id,e.installation_id,e.received_at,e.operation,
            e.schema_version,e.projection_status,e.projection_error,s.id registered_store,c.name company_name,
            s.name store_name,%s province,
            case when pg_input_is_valid(e.payload,'jsonb') then e.payload::jsonb end body
          from saas_sync_event e join saas_company c on c.id=e.company_id
          left join saas_store s on s.id=e.store_id and s.company_id=e.company_id
          where e.entity_type='STOCK_MOVEMENT' %s
        ), parsed as (
          select raw.*,body->>'tipo' type,
            case when body->>'productoId' ~ '%s' then (body->>'productoId')::uuid end product_id,
            case when body->>'almacenId' ~ '%s' then (body->>'almacenId')::uuid end warehouse_id,
            case when length(body->>'cantidad') <= 40 and body->>'cantidad' ~ '^-?[0-9]{1,16}([.][0-9]{1,3}0*)?$'
              then (body->>'cantidad')::numeric(19,3) end quantity,
            case when body->>'creadoEn' ~ '%s' and pg_input_is_valid(body->>'creadoEn','timestamptz')
              then (left(trim(trailing 'Z' from body->>'creadoEn'),26)||'Z')::timestamptz end occurred_at
          from raw
        ), checked as (
          select *, coalesce(registered_store is not null and operation='CREAR' and schema_version=1
            and projection_status in ('PROJECTED','IGNORED') and product_id is not null and warehouse_id is not null
            and quantity<>0 and occurred_at is not null and type in (%s)
            and case when type in ('TRANSFERENCIA_SALIDA','SALIDA_ALMACEN') then quantity<0
                     when type in ('TRANSFERENCIA_ENTRADA','ENTRADA_ALMACEN') then quantity>0
                     else true end,false) valid
          from parsed
        ), identities as (
          select company_id,store_id,entity_id,count(*) receipts,count(*) filter(where not valid) malformed,
            (count(distinct case when valid then jsonb_set(body,'{cantidad}',to_jsonb(quantity)) else body end)>1
                or bool_or(projection_error is not null)) conflict
          from checked group by company_id,store_id,entity_id
        ), canonical as (
          select distinct on (m.company_id,m.store_id,m.entity_id)
            m.company_id,m.store_id,m.entity_id,m.installation_id,m.received_at,m.company_name,m.store_name,m.province,
            m.product_id,m.warehouse_id,m.type,m.quantity,m.occurred_at
          from checked m join identities g using(company_id,store_id,entity_id)
          where g.malformed=0 and not g.conflict %s
          order by m.company_id,m.store_id,m.entity_id,m.received_at,m.event_id
        ), facts as (
          select *, (occurred_at at time zone 'UTC')::date business_date from canonical
        )
        """
                .formatted(
                        AnalyticsScope.PROVINCE,
                        scopeFilter,
                        UUID_PATTERN,
                        UUID_PATTERN,
                        INSTANT_PATTERN,
                        String.join(
                                ",",
                                Arrays.stream(Type.values())
                                        .map(type -> "'" + type.name() + "'")
                                        .toList()),
                        businessFilters);
    }

    static final String DIMENSIONS =
            """
             left join lateral (select id from saas_inventory_snapshot ss where ss.company_id=f.company_id
                 and ss.store_id=f.store_id and ss.installation_id=f.installation_id and ss.completed_at is not null
                 order by ss.captured_at desc,ss.snapshot_id desc limit 1) snap on true
             left join saas_inventory_warehouse w on w.snapshot=snap.id and w.warehouse_id=f.warehouse_id
            """;
    static final String PRODUCT =
            " left join saas_inventory_product p on p.snapshot=snap.id and"
                + " p.product_id=f.product_id ";

    static String summary(GroupBy group) {
        String key =
                switch (group) {
                    case TYPE -> "f.type";
                    case STORE -> "f.store_id::text";
                    case PROVINCE -> "f.province";
                    case DAY -> "(f.business_date - period.start_date)::text";
                    case WAREHOUSE ->
                            "concat_ws('/',f.store_id,coalesce(f.installation_id::text,'UNKNOWN'),f.warehouse_id)";
                };
        String label =
                switch (group) {
                    case STORE -> "f.store_name";
                    case WAREHOUSE ->
                            "concat(f.store_name,' / ',coalesce(w.name,f.warehouse_id::text))";
                    default -> key;
                };
        String buckets =
                group == GroupBy.DAY
                        ? """
                        select n::text key,n::text label,coalesce(c.cnt,0) cnt,coalesce(c.other_cnt,0) other_cnt
                        from generate_series(0,:days-1) n left join counts c on c.key=n::text
                        """
                        : "select * from counts";
        String order = group == GroupBy.DAY ? "key::int" : "lower(label),key";
        return """
        , periods(side,start_date,end_date) as (
           values(0,cast(:from as date),cast(:to as date)),(1,cast(:otherFrom as date),cast(:otherTo as date))
        ), counts as (
           select %s key,min(%s) label,count(*) filter(where period.side=0) cnt,
             count(*) filter(where period.side=1) other_cnt
           from facts f join periods period on f.business_date between period.start_date and period.end_date
           %s group by 1
        ), buckets as (%s), page_rows as (
           select * from buckets order by %s limit :size offset :offset
        ), totals as (
           select count(*) total,coalesce(sum(cnt),0) current_count,coalesce(sum(other_cnt),0) other_count from buckets
        ), coverage as (
           select coalesce(sum(malformed),0) malformed,count(*) filter(where conflict) conflicts,
             coalesce(sum(receipts-1) filter(where malformed=0 and not conflict),0) duplicates from identities
        )
        select * from totals cross join coverage left join page_rows on true order by %s
        """
                .formatted(
                        key,
                        label,
                        group == GroupBy.WAREHOUSE ? DIMENSIONS : "",
                        buckets,
                        order,
                        order);
    }

    static String ledger(Sort sort, Direction direction) {
        String order =
                switch (sort) {
                    case OCCURRED_AT -> "occurred_at";
                    case RECEIVED_AT -> "received_at";
                    case TYPE -> "type";
                    case PRODUCT -> "lower(product_name)";
                    case WAREHOUSE -> "lower(warehouse_name)";
                };
        order += " " + direction.name() + ",company_id,store_id,entity_id";
        return """
        , selected as (select * from facts where business_date between :from and :to),
          page_rows as (select f.*,coalesce(w.name,f.warehouse_id::text) warehouse_name,
                coalesce(p.name,f.product_id::text) product_name
            from selected f %s %s)
        select * from (select count(*) total from selected) totals left join
            (select * from page_rows order by %s limit :size offset :offset) page on true order by %s
        """
                .formatted(DIMENSIONS, PRODUCT, order, order);
    }
}
